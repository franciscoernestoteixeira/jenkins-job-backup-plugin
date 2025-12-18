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
import org.antlr.v4.runtime.misc.NotNull;
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
    // Export

    public List<Model.ItemRow> getAllItems() {
        ensureAdmin();

        // 1) Real items (jobs + real folders)
        List<Model.ItemRow> realRows = Jenkins.get().getAllItems(Item.class).stream()
                .filter(Objects::nonNull)
                .filter(i -> (i instanceof TopLevelItem) || (i instanceof Folder) || (i instanceof AbstractItem))
                .map(Model.ItemRow::from)
                .collect(Collectors.toList());

        // 2) Synthetic folder rows for all ancestors (so user can click folder paths)
        Map<String, Model.ItemRow> byFullName = new HashMap<>();

        for (Model.ItemRow r : realRows) {
            byFullName.put(r.getFullName(), r);

            // add ancestors as Folder rows if missing
            String p = r.getParentFullName();
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
        if (fullName == null) return null;
        int idx = fullName.lastIndexOf('/');
        if (idx <= 0) return null;
        return fullName.substring(0, idx);
    }

    private static Comparator<Model.ItemRow> itemRowComparator() {
        return new Comparator<Model.ItemRow>() {
            @Override
            public int compare(Model.ItemRow a, Model.ItemRow b) {
                int c = sortKey(a).compareToIgnoreCase(sortKey(b));
                if (c != 0) return c;
                return a.getFullName().compareToIgnoreCase(b.getFullName());
            }
        };
    }

    private static String sortKey(Model.ItemRow r) {
        String name = (r.getFullName() == null) ? "" : r.getFullName();

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

        // Real rows from extracted items
        List<Model.ImportCandidate> realRows = candidates.stream()
                .map(c -> {
                    boolean exists = Jenkins.get().getItemByFullName(c.fullName()) != null;
                    return Model.ImportCandidate.from(c.fullName(), c.configXmlPath().toString(), exists);
                })
                .collect(Collectors.toList());

        // Add synthetic folders for all ancestors
        Map<String, Model.ImportCandidate> byFullName = new HashMap<>();

        for (Model.ImportCandidate r : realRows) {
            byFullName.put(r.getFullName(), r);

            String p = r.getParentFullName();
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
        return new Comparator<Model.ImportCandidate>() {
            @Override
            public int compare(Model.ImportCandidate a, Model.ImportCandidate b) {
                int c = sortKey(a).compareToIgnoreCase(sortKey(b));
                if (c != 0) return c;
                return a.getFullName().compareToIgnoreCase(b.getFullName());
            }
        };
    }

    private static String sortKey(Model.ImportCandidate r) {
        String name = (r.getFullName() == null) ? "" : r.getFullName();

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
