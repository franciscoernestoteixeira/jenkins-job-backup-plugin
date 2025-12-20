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

@Extension
public class JobBackupManagementLink extends ManagementLink {

    private final ExportService exportService = new ExportService();
    private final ImportSessionService importSessionService = new ImportSessionService();
    private final ApplyService applyService = new ApplyService();

    // Formatter: 2025-12-11T01_42_36.025Z
    private static final DateTimeFormatter EXPORT_TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH_mm_ss.SSS'Z'")
                    .withZone(ZoneOffset.UTC);

    // ----------------------------
    // Management link metadata (shows in Manage Jenkins)

    @Override
    public String getIconFileName() {
        return "symbol-archive-outline plugin-ionicons-api";
    }

    @Override
    public String getDisplayName() {
        return "Job Backup & Restore";
    }

    @Override
    public String getDescription() {
        return "Export selected jobs and folders into a ZIP archive and import them into this Jenkins instance.";
    }

    @Override
    public String getUrlName() {
        // This will be under /manage/<urlName>
        return "job-backup";
    }

    @Override
    public @NonNull Category getCategory() {
        return Category.TOOLS;
    }

//    @Override
//    public @NonNull Permission getRequiredPermission() {
//        return Jenkins.ADMINISTER;
//    }

    private void ensureAdmin() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
    }

    private static void redirectWithError(StaplerRequest req, StaplerResponse rsp, String relativePath, String message) throws IOException {
        var encoded = URLEncoder.encode(message, StandardCharsets.UTF_8);
        var base = req.getContextPath() + (relativePath.startsWith("/") ? relativePath : ("/" + relativePath));
        var sep = base.contains("?") ? "&" : "?";

        rsp.sendRedirect2(base + sep + "error=" + encoded);
    }

    private static void redirectToImportWithError(StaplerRequest req, StaplerResponse rsp, String message) throws IOException {
        redirectWithError(req, rsp, "/manage/job-backup/import", message);
    }

    /**
     * Best-effort cleanup for invalid/empty sessions.
     * This avoids orphan session folders if the uploaded ZIP is empty/invalid.
     */
    private void tryDeleteSessionQuietly(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        try {
            // Add this method to ImportSessionService (recommended).
            // If you already have an equivalent, call it here.
            importSessionService.deleteSession(sessionId);
        } catch (Exception ignored) {
            // swallow: cleanup is best-effort only
        }
    }

    // ----------------------------
    // Export

    public List<Model.ItemRow> getAllItems() {
        ensureAdmin();

        // 1) Real items (jobs + real folders)
        var realRows = Jenkins.get().getAllItems(Item.class).stream()
                .filter(Objects::nonNull)
                .filter(i -> (i instanceof TopLevelItem) || (i instanceof Folder) || (i instanceof AbstractItem))
                .map(Model.ItemRow::from)
                .collect(Collectors.toList());

        // 2) Synthetic folder rows for all ancestors (so user can click folder paths)
        var byFullName = new HashMap<String, Model.ItemRow>();

        for (var r : realRows) {
            byFullName.put(r.getFullName(), r);

            // add ancestors as Folder rows if missing
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

    private static Comparator<Model.ItemRow> itemRowComparator() {
        return (a, b) -> {
            int c = sortKey(a).compareToIgnoreCase(sortKey(b));
            if (c != 0) {
                return c;
            }

            return a.getFullName().compareToIgnoreCase(b.getFullName());
        };
    }

    private static String sortKey(Model.ItemRow r) {
        var name = (r.getFullName() == null) ? "" : r.getFullName();

        // Folder as prefix path so it sorts before its children
        if (r.isFolder() && !name.endsWith("/")) {
            return name + "/";
        }

        return name;
    }

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

        var timestamp = EXPORT_TS_FORMAT.format(Instant.now());
        var filename = "job-backup-" + timestamp + ".zip";

        rsp.addHeader(
                "Content-Disposition",
                "attachment; filename=" + filename
        );

        exportService.streamZip(Arrays.asList(selected), rsp.getOutputStream());
    }

    // ----------------------------
    // Import: upload

    @RequirePOST
    public void doUploadZip(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        ensureAdmin();

        var file = req.getFileItem("zipFile");

        if (file == null || file.getSize() == 0) {
            // UX: show a notification on the Import screen.
            redirectToImportWithError(req, rsp, "No ZIP uploaded.");
            return;
        }

        var sessionId = importSessionService.createSession();
        var sessionDir = importSessionService.sessionDir(sessionId);
        Files.createDirectories(sessionDir);

        var uploaded = sessionDir.resolve("upload.zip");

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, uploaded, StandardCopyOption.REPLACE_EXISTING);
        }

        var unzipDir = sessionDir.resolve("unzipped");
        Files.createDirectories(unzipDir);

        try {
            ZipSlipSafeUnzip.unzip(uploaded, unzipDir);
        } catch (Exception e) {
            tryDeleteSessionQuietly(sessionId);
            redirectToImportWithError(req, rsp, "Invalid ZIP file (cannot unzip).");
            return;
        }

        // IMPORTANT: if the ZIP unzips but contains nothing importable, fail fast.
        var candidates = importSessionService.findCandidates(unzipDir);
        if (candidates == null || candidates.isEmpty()) {
            tryDeleteSessionQuietly(sessionId);
            redirectToImportWithError(req, rsp, "ZIP has no importable jobs/folders (no config.xml entries found).");
            return;
        }

        rsp.sendRedirect(req.getContextPath() + "/manage/job-backup/preview?sessionId=" + sessionId);
    }

    public void doImport(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        ensureAdmin();

        req.getView(this, "import.jelly").forward(req, rsp);
    }

    // ----------------------------
    // Import: preview

    public List<Model.ImportCandidate> getCandidates() throws IOException {
        ensureAdmin();

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

        // Real rows from extracted items
        var realRows = candidates.stream()
                .map(c -> {
                    boolean exists = Jenkins.get().getItemByFullName(c.fullName()) != null;
                    return Model.ImportCandidate.from(c.fullName(), c.configXmlPath().toString(), exists);
                })
                .toList();

        // Add synthetic folders for all ancestors
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

        // Hierarchical prefix order
        return byFullName.values().stream()
                .sorted(importCandidateComparator())
                .collect(Collectors.toList());
    }

    private static Comparator<Model.ImportCandidate> importCandidateComparator() {
        return (a, b) -> {
            int c = sortKey(a).compareToIgnoreCase(sortKey(b));
            if (c != 0) {
                return c;
            }

            return a.getFullName().compareToIgnoreCase(b.getFullName());
        };
    }

    private static String sortKey(Model.ImportCandidate r) {
        var name = (r.getFullName() == null) ? "" : r.getFullName();

        // folders behave like prefix paths: "A/B/" sorts before "A/B/job1"
        if (r.isFolder() && !name.endsWith("/")) {
            return name + "/";
        }

        return name;
    }

    // ----------------------------
    // Import: apply

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

        var unzipDir = importSessionService.unzipDir(sessionId);
        var configByFullName = importSessionService.indexConfigXml(unzipDir);

        var toApply = Arrays.asList(selected);
        var result = applyService.apply(toApply, configByFullName);

        importSessionService.writeResult(sessionId, result);

        rsp.sendRedirect(req.getContextPath() + "/manage/job-backup/import?sessionId=" + sessionId);
    }

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

    // Expose readResult to Jelly (since field is private)
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
