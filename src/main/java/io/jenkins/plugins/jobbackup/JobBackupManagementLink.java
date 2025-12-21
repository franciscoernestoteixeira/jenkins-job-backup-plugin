/*
 * MIT License
 *
 * Copyright (c) 2025
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.jenkins.plugins.jobbackup;

import com.cloudbees.hudson.plugins.folder.Folder;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractItem;
import hudson.model.Item;
import hudson.model.ManagementLink;
import hudson.model.TopLevelItem;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Jenkins "Manage Jenkins" entry point for the Job Backup &amp; Restore plugin.
 *
 * <h2>What this class provides</h2>
 * As a {@link ManagementLink}, this class:
 * <ul>
 *   <li>Registers a Tools menu entry under <em>Manage Jenkins</em></li>
 *   <li>Renders UI pages via Jelly views:
 *     <ul>
 *       <li>{@code index.jelly} - export screen</li>
 *       <li>{@code import.jelly} - import landing/result screen</li>
 *       <li>{@code preview.jelly} - import preview selection screen</li>
 *     </ul>
 *   </li>
 *   <li>Implements the main HTTP endpoints for export and import:
 *     <ul>
 *       <li>{@code doExportZip} - streams a ZIP of selected items</li>
 *       <li>{@code doUploadZip} - accepts ZIP upload and prepares a preview session</li>
 *       <li>{@code doPreview} - renders preview for a session</li>
 *       <li>{@code doApplySelected} - applies selected configs into Jenkins</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h2>Security model</h2>
 * This link is intended for administrators only. Most methods call {@link #ensureAdmin()},
 * which enforces {@link Jenkins#ADMINISTER}.
 *
 * <p>Notes:</p>
 * <ul>
 *   <li>This class performs permission checks; the internal services focus on mechanics.</li>
 *   <li>All mutating endpoints are annotated with {@link RequirePOST}.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>Export and import share the same "prefix selection" semantics:
 *       selecting {@code "A/B"} implies all descendants under {@code "A/B/"}.</li>
 *   <li>Import is session-based to support multi-step UI flows without persisting large data in memory.</li>
 * </ul>
 */
@Extension
public class JobBackupManagementLink extends ManagementLink {

    /**
     * Service responsible for streaming ZIP exports.
     */
    private final ExportService exportService = new ExportService();

    /**
     * Service responsible for managing import sessions and candidate discovery.
     */
    private final ImportSessionService importSessionService = new ImportSessionService();

    /**
     * Service responsible for applying extracted {@code config.xml} selections to Jenkins.
     */
    private final ApplyService applyService = new ApplyService();

    /**
     * Formatter for export timestamp used in the ZIP filename.
     *
     * <p>Example: {@code 2025-12-11T01_42_36.025Z}</p>
     * <p>Underscores are used instead of {@code ':'} to keep filenames portable across platforms.</p>
     */
    private static final DateTimeFormatter EXPORT_TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH_mm_ss.SSS'Z'")
                    .withZone(ZoneOffset.UTC);

    // ----------------------------
    // Management link metadata (shows in Manage Jenkins)

    /**
     * Returns the icon used by Jenkins to render this management link in the UI.
     *
     * <p>The value is a Jenkins icon "src" using the Ionicons plugin integration.</p>
     *
     * @return icon identifier string, or null to hide the link.
     */
    @Override
    public String getIconFileName() {
        return "symbol-archive-outline plugin-ionicons-api";
    }

    /**
     * Display name for the management link shown in Jenkins UI.
     *
     * @return "Job Backup & Restore".
     */
    @Override
    public String getDisplayName() {
        return "Job Backup & Restore";
    }

    /**
     * Short description shown in the management UI.
     *
     * @return a concise description of the plugin.
     */
    @Override
    public String getDescription() {
        return "Export selected jobs and folders into a ZIP archive and import them into this Jenkins instance.";
    }

    /**
     * URL segment for this management link.
     *
     * <p>This will be reachable under:</p>
     * <pre>{@code
     * /manage/<urlName>
     * }</pre>
     *
     * @return "job-backup".
     */
    @Override
    public String getUrlName() {
        // This will be under /manage/<urlName>
        return "job-backup";
    }

    /**
     * Category where Jenkins will display this management link.
     *
     * @return {@link Category#TOOLS}.
     */
    @Override
    public @NonNull Category getCategory() {
        return Category.TOOLS;
    }

    //    @Override
    //    public @NonNull Permission getRequiredPermission() {
    //        return Jenkins.ADMINISTER;
    //    }

    /**
     * Enforces administrator permission for all entry points in this management link.
     *
     * <p>This method should be called at the beginning of any method that:
     * <ul>
     *   <li>Returns lists of jobs/folders (sensitive metadata)</li>
     *   <li>Streams configuration files</li>
     *   <li>Uploads, previews, or applies configuration</li>
     * </ul>
     * </p>
     *
     * @throws hudson.security.AccessDeniedException if the current user lacks ADMINISTER permission.
     */
    private void ensureAdmin() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
    }

    /**
     * Redirects to a Jenkins-relative page with an {@code error=} query parameter.
     *
     * <p>This supports UI-friendly error reporting by rendering a "notification banner" instead of
     * returning a Jetty error page.</p>
     *
     * <p>Example redirect target:</p>
     * <pre>{@code
     * /jenkins/manage/job-backup/import?error=No%20ZIP%20uploaded.
     * }</pre>
     *
     * @param req current Stapler request.
     * @param rsp current Stapler response.
     * @param relativePath path relative to the Jenkins context, e.g. {@code "/manage/job-backup/import"}.
     * @param message human-readable error message (will be URL-encoded).
     * @throws IOException if redirect cannot be sent.
     */
    private static void redirectWithError(StaplerRequest req, StaplerResponse rsp, String relativePath, String message) throws IOException {
        var encoded = URLEncoder.encode(message, StandardCharsets.UTF_8);
        var base = req.getContextPath() + (relativePath.startsWith("/") ? relativePath : ("/" + relativePath));
        var sep = base.contains("?") ? "&" : "?";

        rsp.sendRedirect2(base + sep + "error=" + encoded);
    }

    /**
     * Convenience redirect helper targeting the Import landing page.
     *
     * @param req current request.
     * @param rsp current response.
     * @param message error message to show.
     * @throws IOException if redirect cannot be sent.
     */
    private static void redirectToImportWithError(StaplerRequest req, StaplerResponse rsp, String message) throws IOException {
        redirectWithError(req, rsp, "/manage/job-backup/import", message);
    }

    /**
     * Best-effort cleanup for invalid/empty sessions.
     *
     * <p>The import flow creates session directories on disk. If an upload fails validation
     * (empty ZIP, unzipping error, no candidates), this method attempts to remove the session
     * to avoid leaving orphan folders behind.</p>
     *
     * <p>Cleanup is best-effort by design: failure to delete should not block the user flow.</p>
     *
     * @param sessionId session identifier to delete.
     */
    private void tryDeleteSessionQuietly(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        try {
            importSessionService.deleteSession(sessionId);
        } catch (Exception ignored) {
            // Swallow: cleanup is best-effort only.
        }
    }

    // ----------------------------
    // Export

    /**
     * Returns all exportable items (including synthetic folder rows) for Jelly rendering.
     *
     * <h2>What is returned</h2>
     * <ul>
     *   <li>Real Jenkins items (jobs + real folders) as {@link Model.ItemRow} entries</li>
     *   <li>Synthetic folder rows for all ancestor paths, so the user can select folder paths</li>
     * </ul>
     *
     * <p>The returned list is sorted in hierarchical prefix order so it can be rendered as a tree.</p>
     *
     * @return list of rows for the Export screen.
     */
    public List<Model.ItemRow> getAllItems() {
        ensureAdmin();

        // 1) Real items (jobs + real folders).
        // We include items that are TopLevelItem, Folder, or AbstractItem to cover the main job/folder types.
        var realRows = Jenkins.get().getAllItems(Item.class).stream()
                .filter(Objects::nonNull)
                .filter(i -> (i instanceof TopLevelItem) || (i instanceof Folder) || (i instanceof AbstractItem))
                .map(Model.ItemRow::from)
                .collect(Collectors.toList());

        // 2) Synthetic folder rows for all ancestors (so user can click folder paths).
        // Use a map keyed by fullName to avoid duplicates.
        var byFullName = new HashMap<String, Model.ItemRow>();

        for (var r : realRows) {
            byFullName.put(r.getFullName(), r);

            // Add ancestors as folder rows if missing.
            var p = r.getParentFullName();
            while (p != null && !p.isBlank()) {
                byFullName.putIfAbsent(p, Model.ItemRow.folder(p));
                p = parentOf(p);
            }
        }

        // 3) Sort in hierarchical (prefix) order: A/ < A/B/ < A/B/job1 < C/ < C/D < C/E ...
        return byFullName.values().stream()
                .sorted(itemRowComparator())
                .collect(Collectors.toList());
    }

    /**
     * Computes the parent path for a Jenkins full name.
     *
     * @param fullName Jenkins full name (folder/job path).
     * @return parent full name, or null if the item is top-level or input is null/invalid.
     */
    private static String parentOf(String fullName) {
        if (fullName == null) {
            return null;
        }

        int idx = fullName.lastIndexOf('/');
        if (idx <= 0) {
            return null;
        }

        return fullName.substring(0, idx);
    }

    /**
     * Comparator that sorts {@link Model.ItemRow} entries in a tree-friendly hierarchical order.
     *
     * <p>Folders are treated as prefix paths by appending a trailing slash to the sort key,
     * ensuring folder nodes appear before their children.</p>
     *
     * @return comparator for export rows.
     */
    private static Comparator<Model.ItemRow> itemRowComparator() {
        return (a, b) -> {
            int c = sortKey(a).compareToIgnoreCase(sortKey(b));
            if (c != 0) {
                return c;
            }

            // Tie-breaker: stable ordering for identical sort keys.
            return a.getFullName().compareToIgnoreCase(b.getFullName());
        };
    }

    /**
     * Returns a sort key for hierarchical ordering.
     *
     * <p>For folders, we append {@code "/"} so that:</p>
     * <ul>
     *   <li>{@code "A/B/"} sorts before {@code "A/B/job1"}</li>
     *   <li>{@code "A/"} sorts before {@code "A/job"}</li>
     * </ul>
     *
     * @param r row to produce a key for.
     * @return sort key (never null).
     */
    private static String sortKey(Model.ItemRow r) {
        var name = (r.getFullName() == null) ? "" : r.getFullName();

        // Folder as prefix path so it sorts before its children.
        if (r.isFolder() && !name.endsWith("/")) {
            return name + "/";
        }

        return name;
    }

    /**
     * Streams a ZIP export of selected items.
     *
     * <p>This endpoint is POST-only to prevent accidental export via links and to align with Jenkins
     * CSRF protection expectations.</p>
     *
     * <p>The response uses {@code application/zip} and a timestamped filename.</p>
     *
     * @param req request containing {@code selected} parameters.
     * @param rsp response where ZIP will be written.
     * @throws IOException if streaming fails.
     */
    @RequirePOST
    public void doExportZip(StaplerRequest req, StaplerResponse rsp) throws IOException {
        ensureAdmin();

        var selected = req.getParameterValues("selected");

        if (selected == null || selected.length == 0) {
            // UX: validation feedback should be rendered as a Jenkins notification, not a Jetty error page.
            redirectWithError(req, rsp, "/manage/job-backup/", "No items selected.");
            return;
        }

        rsp.setContentType("application/zip");

        // Timestamped filename: job-backup-2025-12-11T01_42_36.025Z.zip
        var timestamp = EXPORT_TS_FORMAT.format(Instant.now());
        var filename = "job-backup-" + timestamp + ".zip";

        rsp.addHeader(
                "Content-Disposition",
                "attachment; filename=" + filename
        );

        // Delegate ZIP generation to ExportService.
        exportService.streamZip(Arrays.asList(selected), rsp.getOutputStream());
    }

    // ----------------------------
    // Import: upload

    /**
     * Handles ZIP upload for an import session.
     *
     * <h2>Flow</h2>
     * <ol>
     *   <li>Create a new session id and directory</li>
     *   <li>Save the uploaded ZIP to disk as {@code upload.zip}</li>
     *   <li>Unzip it safely into {@code unzipped/}</li>
     *   <li>Discover candidates; if none, delete session and return a UI error</li>
     *   <li>Redirect to preview screen with {@code sessionId}</li>
     * </ol>
     *
     * <p>This endpoint is POST-only and requires ADMINISTER permission.</p>
     *
     * @param req request containing file item {@code zipFile}.
     * @param rsp response used for redirects.
     * @throws IOException on I/O errors writing the upload file or creating directories.
     * @throws ServletException on Stapler multipart handling errors.
     */
    @RequirePOST
    public void doUploadZip(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        ensureAdmin();

        // Field name must match the file input name in Jelly.
        var file = req.getFileItem("zipFile");

        if (file == null || file.getSize() == 0) {
            // UX: show a notification on the Import screen.
            redirectToImportWithError(req, rsp, "No ZIP uploaded.");
            return;
        }

        // Create session and allocate filesystem storage under $JENKINS_HOME.
        var sessionId = importSessionService.createSession();
        var sessionDir = importSessionService.sessionDir(sessionId);
        Files.createDirectories(sessionDir);

        // Persist the uploaded ZIP file.
        var uploaded = sessionDir.resolve("upload.zip");

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, uploaded, StandardCopyOption.REPLACE_EXISTING);
        }

        // Unzip destination directory.
        var unzipDir = sessionDir.resolve("unzipped");
        Files.createDirectories(unzipDir);

        // Safe unzip with zip-slip protections.
        try {
            ZipSlipSafeUnzip.unzip(uploaded, unzipDir);
        } catch (Exception e) {
            tryDeleteSessionQuietly(sessionId);
            redirectToImportWithError(req, rsp, "Invalid ZIP file (cannot unzip).");
            return;
        }

        // If the ZIP unzips but contains nothing importable, fail fast.
        var candidates = importSessionService.findCandidates(unzipDir);
        if (candidates == null || candidates.isEmpty()) {
            tryDeleteSessionQuietly(sessionId);
            redirectToImportWithError(req, rsp, "ZIP has no importable jobs/folders (no config.xml entries found).");
            return;
        }

        // Redirect to preview step.
        rsp.sendRedirect(req.getContextPath() + "/manage/job-backup/preview?sessionId=" + sessionId);
    }

    /**
     * Renders the Import landing page ({@code import.jelly}).
     *
     * <p>This is typically linked from the export page ("Import") and is also the screen
     * that displays apply results for a session.</p>
     *
     * @param req current request.
     * @param rsp current response.
     * @throws IOException if forwarding fails.
     * @throws ServletException if forwarding fails.
     */
    public void doImport(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        ensureAdmin();

        req.getView(this, "import.jelly").forward(req, rsp);
    }

    // ----------------------------
    // Import: preview

    /**
     * Returns import candidates for the current preview session.
     *
     * <p>This method is typically called from {@code preview.jelly} via Jelly binding.</p>
     *
     * <h2>Behavior</h2>
     * <ul>
     *   <li>Reads {@code sessionId} from the current request</li>
     *   <li>Discovers extracted config.xml candidates</li>
     *   <li>Maps candidates to {@link Model.ImportCandidate} including:
     *     <ul>
     *       <li>folder detection (from config.xml root element)</li>
     *       <li>existence check in current Jenkins</li>
     *     </ul>
     *   </li>
     *   <li>Adds synthetic folder nodes for ancestor paths</li>
     *   <li>Sorts in hierarchical prefix order for tree rendering</li>
     * </ul>
     *
     * <p>If session is missing/invalid, returns an empty list (UI stays stable without Jetty errors).</p>
     *
     * @return list of candidates for preview (possibly empty).
     * @throws IOException if file walking fails while discovering candidates.
     */
    public List<Model.ImportCandidate> getCandidates() throws IOException {
        ensureAdmin();

        // sessionId is passed via query param when redirecting from upload step.
        var sessionId = Stapler.getCurrentRequest().getParameter("sessionId");

        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }

        // If preview is hit with an invalid/expired session, do not throw a Jetty error page.
        // doPreview() will redirect with an error; this is just a safety net for Jelly calls.
        if (!importSessionService.sessionExists(sessionId)) {
            return List.of();
        }

        var unzipDir = importSessionService.unzipDir(sessionId);
        var candidates = importSessionService.findCandidates(unzipDir);

        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // Real rows from extracted items.
        var realRows = candidates.stream()
                .map(c -> {
                    // Determine whether the item already exists in this Jenkins instance.
                    boolean exists = Jenkins.get().getItemByFullName(c.fullName()) != null;
                    return Model.ImportCandidate.from(c.fullName(), c.configXmlPath().toString(), exists);
                })
                .toList();

        // Add synthetic folders for all ancestors.
        var byFullName = new HashMap<String, Model.ImportCandidate>();

        for (Model.ImportCandidate r : realRows) {
            byFullName.put(r.getFullName(), r);

            var p = r.getParentFullName();
            while (p != null && !p.isBlank()) {
                boolean folderExists = Jenkins.get().getItemByFullName(p) != null;
                byFullName.putIfAbsent(p, Model.ImportCandidate.folder(p, folderExists));
                p = parentOf(p);
            }
        }

        // Hierarchical prefix order.
        return byFullName.values().stream()
                .sorted(importCandidateComparator())
                .collect(Collectors.toList());
    }

    /**
     * Comparator for rendering import candidates in a hierarchical prefix order.
     *
     * @return comparator for preview rows.
     */
    private static Comparator<Model.ImportCandidate> importCandidateComparator() {
        return (a, b) -> {
            int c = sortKey(a).compareToIgnoreCase(sortKey(b));
            if (c != 0) {
                return c;
            }

            // Tie-breaker for stable ordering.
            return a.getFullName().compareToIgnoreCase(b.getFullName());
        };
    }

    /**
     * Sort key used by {@link #importCandidateComparator()}.
     *
     * <p>Folders are treated as prefix paths, so "A/B/" sorts before "A/B/job1".</p>
     *
     * @param r candidate row.
     * @return sort key (never null).
     */
    private static String sortKey(Model.ImportCandidate r) {
        var name = (r.getFullName() == null) ? "" : r.getFullName();

        // Folders behave like prefix paths: "A/B/" sorts before "A/B/job1".
        if (r.isFolder() && !name.endsWith("/")) {
            return name + "/";
        }

        return name;
    }

    // ----------------------------
    // Import: apply

    /**
     * Applies the user selection for an import preview session.
     *
     * <h2>Flow</h2>
     * <ol>
     *   <li>Validate session id</li>
     *   <li>Validate selection list</li>
     *   <li>Index config.xml files by fullName</li>
     *   <li>Apply selection using {@link ApplyService}</li>
     *   <li>Persist result using {@link ImportSessionService}</li>
     *   <li>Redirect to import page to display results</li>
     * </ol>
     *
     * <p>This endpoint is POST-only and requires ADMINISTER permission.</p>
     *
     * @param req request containing {@code sessionId} and {@code selected[]} parameters.
     * @param rsp response used for redirects.
     * @throws IOException if reading candidates or writing results fails.
     */
    @RequirePOST
    public void doApplySelected(StaplerRequest req, StaplerResponse rsp) throws IOException {
        ensureAdmin();

        var sessionId = req.getParameter("sessionId");
        var selected = req.getParameterValues("selected");

        if (sessionId == null || sessionId.isBlank()) {
            // UX: sessionId is required to apply a preview; keep the user on Import.
            redirectToImportWithError(req, rsp, "Missing sessionId.");
            return;
        }

        if (!importSessionService.sessionExists(sessionId)) {
            redirectToImportWithError(req, rsp, "Unknown session.");
            return;
        }

        if (selected == null || selected.length == 0) {
            // UX: validation feedback should be rendered as a Jenkins notification.
            redirectWithError(req, rsp, "/manage/job-backup/preview?sessionId=" + sessionId, "No items selected.");
            return;
        }

        // Build fullName -> config.xml path mapping.
        var unzipDir = importSessionService.unzipDir(sessionId);
        var configByFullName = importSessionService.indexConfigXml(unzipDir);

        // Apply selection using prefix semantics (implemented in ApplyService).
        var toApply = Arrays.asList(selected);
        var result = applyService.apply(toApply, configByFullName);

        // Persist result for later display in import.jelly.
        importSessionService.writeResult(sessionId, result);

        // Redirect to import landing page, which can display the stored result.
        rsp.sendRedirect(req.getContextPath() + "/manage/job-backup/import?sessionId=" + sessionId);
    }

    /**
     * Renders the Preview page ({@code preview.jelly}) for a given session.
     *
     * <p>This method performs validation and redirects to the Import screen with an error
     * rather than rendering Jetty error pages.</p>
     *
     * @param req request containing {@code sessionId}.
     * @param rsp response for redirect/forward.
     * @throws IOException if redirect/forward fails.
     * @throws ServletException if forwarding fails.
     */
    public void doPreview(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        ensureAdmin();

        var sessionId = req.getParameter("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            redirectToImportWithError(req, rsp, "Missing sessionId.");
            return;
        }

        if (!importSessionService.sessionExists(sessionId)) {
            redirectToImportWithError(req, rsp, "Unknown session.");
            return;
        }

        var unzipDir = importSessionService.unzipDir(sessionId);
        var candidates = importSessionService.findCandidates(unzipDir);

        if (candidates == null || candidates.isEmpty()) {
            redirectToImportWithError(req, rsp, "Nothing to preview: this session contains no importable jobs/folders.");
            return;
        }

        req.getView(this, "preview.jelly").forward(req, rsp);
    }

    /**
     * Server-side validation method for form fields that reference a session id.
     *
     * <p>Jenkins uses {@code doCheck*} methods to support live form validation.</p>
     *
     * @param value session id to validate (as provided by the form).
     * @return {@link FormValidation#ok()} if valid; otherwise {@link FormValidation#error(String)}.
     */
    public FormValidation doCheckSessionId(@QueryParameter String value) {
        ensureAdmin();

        if (value == null || value.isBlank()) {
            return FormValidation.error("sessionId required");
        }

        if (!importSessionService.sessionExists(value)) {
            return FormValidation.error("Unknown session");
        }

        return FormValidation.ok();
    }

    /**
     * Exposes the apply result to Jelly views.
     *
     * <p>{@code import.jelly} can call this method to show the summary of the last apply operation.</p>
     *
     * <p>This method is defensive: if the session is missing/invalid, it returns empty rather than
     * throwing errors in the UI rendering pipeline.</p>
     *
     * @param sessionId session id query parameter.
     * @return optional apply result, if present and readable.
     */
    public Optional<Model.ApplyResult> readResult(@QueryParameter String sessionId) {
        ensureAdmin();

        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }

        if (!importSessionService.sessionExists(sessionId)) {
            return Optional.empty();
        }

        return importSessionService.readResult(sessionId);
    }

}
