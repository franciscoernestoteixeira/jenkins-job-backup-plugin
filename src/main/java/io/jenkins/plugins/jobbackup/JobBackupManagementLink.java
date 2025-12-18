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

    // ----------------------------
    // UI model

    public List<Model.ItemRow> getAllItems() {
        ensureAdmin();

        return Jenkins.get().getAllItems(Item.class).stream()
                .filter(Objects::nonNull)
                .filter(i -> (i instanceof TopLevelItem) || (i instanceof Folder) || (i instanceof AbstractItem))
                .map(Model.ItemRow::from)
                .sorted(Comparator.comparing(Model.ItemRow::getFullName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    // ----------------------------
    // Export

    @RequirePOST
    public void doExportZip(StaplerRequest req, StaplerResponse rsp) throws IOException {
        ensureAdmin();

        var selected = req.getParameterValues("selected");

        if (selected == null || selected.length == 0) {
            rsp.sendError(400, "No items selected.");
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
            rsp.sendError(400, "No ZIP uploaded.");
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

        ZipSlipSafeUnzip.unzip(uploaded, unzipDir);

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

        var unzipDir = importSessionService.unzipDir(sessionId);
        var candidates = importSessionService.findCandidates(unzipDir);

        return candidates.stream()
                .map(c -> {
                    boolean exists = Jenkins.get().getItemByFullName(c.fullName()) != null;
                    return Model.ImportCandidate.from(c.fullName(), c.configXmlPath().toString(), exists);
                })
                .sorted(Comparator.comparing(Model.ImportCandidate::getFullName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    // ----------------------------
    // Import: apply

    @RequirePOST
    public void doApplySelected(StaplerRequest req, StaplerResponse rsp) throws IOException {
        ensureAdmin();

        var sessionId = req.getParameter("sessionId");
        var selected = req.getParameterValues("selected");

        if (sessionId == null || sessionId.isBlank()) {
            rsp.sendError(400, "Missing sessionId.");
            return;
        }

        if (selected == null || selected.length == 0) {
            rsp.sendError(400, "No items selected to apply.");
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

        return importSessionService.readResult(sessionId);
    }

}
