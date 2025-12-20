package io.jenkins.plugins.jobbackup;

import hudson.model.Item;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.events.XMLEvent;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * UI/view-model helpers shared by Export and Import screens.
 */
public final class Model {

    private Model() {
    }

    // -------------------------------------------------------------------------
    // Shared labeling helpers (keep Export/Import consistent)

    private static final String LABEL_FOLDER = "Folder";
    private static final String LABEL_PIPELINE = "Pipeline";
    private static final String LABEL_FREESTYLE = "Freestyle";
    private static final String LABEL_MAVEN = "Maven";
    private static final String LABEL_MULTIBRANCH_PIPELINE = "Multibranch Pipeline";
    private static final String LABEL_ORG_FOLDER = "Organization Folder";
    private static final String LABEL_JOB = "Job";

    /**
     * Maps config.xml root element to a stable UI label.
     *
     * <p>
     * This is the source of truth for Import. Export (runtime items) maps to the same labels.
     * </p>
     */
    private static String typeLabelFromRootElement(String rootElement, boolean isFolder) {
        if (isFolder) {
            return LABEL_FOLDER;
        }

        String r = (rootElement == null) ? "" : rootElement.trim();
        if (r.isEmpty()) {
            return LABEL_JOB;
        }

        // Known Jenkins roots
        switch (r) {
            case "flow-definition":
                return LABEL_PIPELINE;
            case "project":
                return LABEL_FREESTYLE;
            case "maven2-moduleset":
                return LABEL_MAVEN;
            default:
                break;
        }

        // Some plugins use FQCN as the root element
        String normalized = r.toLowerCase(Locale.ROOT);

        if ("org.jenkinsci.plugins.workflow.multibranch.workflowmultibranchproject".equals(normalized)
                || normalized.endsWith(".workflowmultibranchproject")) {
            return LABEL_MULTIBRANCH_PIPELINE;
        }

        if ("jenkins.branch.organizationfolder".equals(normalized)
                || normalized.endsWith(".organizationfolder")) {
            return LABEL_ORG_FOLDER;
        }

        // Fallback: keep a readable name, but avoid exposing full packages
        return simplifyRootElement(r);
    }

    /**
     * Maps a runtime Jenkins item to the same stable UI labels used by Import.
     *
     * <p>
     * This avoids showing raw implementation class names like "WorkflowJob".
     * </p>
     */
    private static String typeLabelFromItem(Item item, boolean isFolder) {
        if (item == null) {
            return LABEL_JOB;
        }

        if (isFolder) {
            return LABEL_FOLDER;
        }

        // Pipeline job (workflow-job plugin)
        if (isInstanceOf(item, "org.jenkinsci.plugins.workflow.job.WorkflowJob")) {
            return LABEL_PIPELINE;
        }

        // Freestyle (core)
        if (isInstanceOf(item, "hudson.model.FreeStyleProject")) {
            return LABEL_FREESTYLE;
        }

        // Maven (maven-plugin)
        if (isInstanceOf(item, "hudson.maven.MavenModuleSet")) {
            return LABEL_MAVEN;
        }

        // Multibranch pipeline / Organization folder families (branch-api, workflow-multibranch)
        if (isInstanceOf(item, "org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject")) {
            return LABEL_MULTIBRANCH_PIPELINE;
        }
        if (isInstanceOf(item, "jenkins.branch.OrganizationFolder")) {
            return LABEL_ORG_FOLDER;
        }

        // Fallback: do not overfit; keep Jenkins class name, but this is now consistent with import fallback strategy.
        return item.getClass().getSimpleName();
    }

    /**
     * Maps a stable UI label to an Ionicons symbol.
     *
     * <p>
     * Keep this aligned across Export (ItemRow) and Import (ImportCandidate).
     * </p>
     */
    private static String iconSymbolFromTypeLabel(String typeLabel, boolean isFolder) {
        if (isFolder) {
            return "symbol-folder-outline";
        }

        String t = (typeLabel == null) ? "" : typeLabel.trim();
        switch (t) {
            case LABEL_PIPELINE:
                return "symbol-git-branch-outline";
            case LABEL_MULTIBRANCH_PIPELINE:
                return "symbol-git-network-outline";
            case LABEL_ORG_FOLDER:
                return "symbol-business-outline";
            case LABEL_MAVEN:
                return "symbol-hammer-outline";
            case LABEL_FREESTYLE:
                return "symbol-document-outline";
            default:
                return "symbol-document-text-outline";
        }
    }


    private static boolean isInstanceOf(Object o, String fqcn) {
        if (o == null || fqcn == null || fqcn.isBlank()) {
            return false;
        }

        Class<?> c = o.getClass();
        while (c != null) {
            if (fqcn.equals(c.getName())) {
                return true;
            }
            c = c.getSuperclass();
        }

        for (Class<?> i : o.getClass().getInterfaces()) {
            if (fqcn.equals(i.getName())) {
                return true;
            }
        }

        return false;
    }

    private static String simplifyRootElement(String rootElement) {
        if (rootElement == null || rootElement.isBlank()) {
            return LABEL_JOB;
        }
        int idx = rootElement.lastIndexOf('.');
        if (idx >= 0 && idx < rootElement.length() - 1) {
            return rootElement.substring(idx + 1);
        }
        return rootElement;
    }

    private static String parentOf(String fullName) {
        if (fullName == null) return null;
        int idx = fullName.lastIndexOf('/');
        if (idx <= 0) return null;
        return fullName.substring(0, idx);
    }

    // -------------------------------------------------------------------------
    // View models (Jelly-friendly: JavaBean getters)

    public static final class ItemRow {

        private final String fullName;
        private final String parentFullName;   // null when root
        /**
         * A user-friendly type label for UI rendering.
         *
         * <p>
         * This intentionally mirrors the import preview labeling (e.g. "Pipeline" instead of
         * "WorkflowJob").
         * </p>
         */
        private final String type;
        private final boolean folder;          // true for folder nodes

        public ItemRow(String fullName, String parentFullName, String type, boolean folder) {
            this.fullName = fullName;
            this.parentFullName = parentFullName;
            this.type = type;
            this.folder = folder;
        }

        public String getFullName() {
            return fullName;
        }

        public String getParentFullName() {
            return parentFullName;
        }

        public String getType() {
            return type;
        }

        public boolean isFolder() {
            return folder;
        }

        /** Icon symbol used by the export view (Jelly). */
        public String getIconSymbol() {
            return iconSymbolFromTypeLabel(type, folder);
        }

        public static ItemRow folder(String fullName) {
            return new ItemRow(
                    fullName,
                    parentOf(fullName),
                    LABEL_FOLDER,
                    true
            );
        }

        public static ItemRow from(Item item) {
            String fullName = item.getFullName();

            // If the folder plugin is present, this is accurate. If not, it will simply be false.
            boolean isFolder = item instanceof com.cloudbees.hudson.plugins.folder.Folder;

            return new ItemRow(
                    fullName,
                    parentOf(fullName),
                    typeLabelFromItem(item, isFolder),
                    isFolder
            );
        }

        public int getDepth() {
            if (fullName == null || fullName.isEmpty()) {
                return 0;
            }
            int depth = 0;
            for (int i = 0; i < fullName.length(); i++) {
                if (fullName.charAt(i) == '/') {
                    depth++;
                }
            }
            return depth;
        }

        public String getLeafName() {
            if (fullName == null) return "";
            int idx = fullName.lastIndexOf('/');
            return (idx >= 0) ? fullName.substring(idx + 1) : fullName;
        }

    }

    public static final class ImportCandidate {

        private final String fullName;
        private final String parentFullName;   // null when root
        private final String configXmlPath;
        private final boolean exists;
        private final boolean folder;          // true for folder nodes

        /** The root XML element from config.xml (e.g. "flow-definition", "project", "com.cloudbees.hudson.plugins.folder.Folder"). */
        private final String rootElement;

        public ImportCandidate(String fullName,
                               String parentFullName,
                               String configXmlPath,
                               boolean exists,
                               boolean folder,
                               String rootElement) {
            this.fullName = fullName;
            this.parentFullName = parentFullName;
            this.configXmlPath = configXmlPath;
            this.exists = exists;
            this.folder = folder;
            this.rootElement = rootElement;
        }

        public String getFullName() {
            return fullName;
        }

        public String getParentFullName() {
            return parentFullName;
        }

        public String getConfigXmlPath() {
            return configXmlPath;
        }

        public boolean isExists() {
            return exists;
        }

        public boolean isFolder() {
            return folder;
        }

        public String getRootElement() {
            return rootElement;
        }

        /**
         * A user-friendly type label for UI rendering.
         *
         * <p>
         * This is derived from the config.xml root element and matches Export's labeling.
         * </p>
         */
        public String getTypeLabel() {
            return typeLabelFromRootElement(rootElement, folder);
        }

        /**
         * Icon symbol used by the Jelly views.
         *
         * <p>
         * The critical fix is correct folder detection; non-folder jobs can keep a generic icon.
         * </p>
         */
        public String getIconSymbol() {
            return iconSymbolFromTypeLabel(getTypeLabel(), folder);
        }

        public static ImportCandidate folder(String fullName, boolean exists) {
            return new ImportCandidate(
                    fullName,
                    parentOf(fullName),
                    "",          // no config path for synthetic folder nodes
                    exists,
                    true,
                    "synthetic-folder"
            );
        }

        public static ImportCandidate from(String fullName, String configXmlPath, boolean exists) {
            Detection d = detectFromConfigXml(configXmlPath);

            return new ImportCandidate(
                    fullName,
                    parentOf(fullName),
                    configXmlPath,
                    exists,
                    d.folder,
                    d.rootElement
            );
        }

        private static final class Detection {
            final boolean folder;
            final String rootElement;

            private Detection(boolean folder, String rootElement) {
                this.folder = folder;
                this.rootElement = rootElement;
            }
        }

        /**
         * Detect folder-vs-job reliably by reading the first (root) element of config.xml.
         *
         * <p>
         * This avoids false positives from path-based heuristics (every job has a config.xml).
         * </p>
         */
        private static Detection detectFromConfigXml(String configXmlPath) {
            if (configXmlPath == null || configXmlPath.isBlank()) {
                return new Detection(false, "");
            }

            Path p = Paths.get(configXmlPath);
            if (!Files.isRegularFile(p)) {
                return new Detection(false, "");
            }

            String root = "";
            try (InputStream in = new BufferedInputStream(Files.newInputStream(p))) {
                XMLInputFactory f = XMLInputFactory.newFactory();

                // Hardening: prevent XXE / external entity expansion
                try { f.setProperty(XMLInputFactory.SUPPORT_DTD, false); } catch (Exception ignored) { }
                try { f.setProperty("javax.xml.stream.isSupportingExternalEntities", false); } catch (Exception ignored) { }

                XMLEventReader r = f.createXMLEventReader(in);
                while (r.hasNext()) {
                    XMLEvent e = r.nextEvent();
                    if (e.isStartElement()) {
                        root = e.asStartElement().getName().getLocalPart();
                        break;
                    }
                }
            } catch (Exception ignored) {
                // If parsing fails, default to "not folder" so UI doesn't mislabel jobs as folders.
                return new Detection(false, "");
            }

            String normalized = (root == null) ? "" : root.toLowerCase(Locale.ROOT);

            // CloudBees Folder Plugin (most common)
            boolean isFolder = "com.cloudbees.hudson.plugins.folder.folder".equals(normalized)
                    || normalized.endsWith(".folder");

            return new Detection(isFolder, root == null ? "" : root);
        }

        public int getDepth() {
            if (fullName == null || fullName.isEmpty()) {
                return 0;
            }
            int depth = 0;
            for (int i = 0; i < fullName.length(); i++) {
                if (fullName.charAt(i) == '/') {
                    depth++;
                }
            }
            return depth;
        }

        public String getLeafName() {
            if (fullName == null) return "";
            int idx = fullName.lastIndexOf('/');
            return (idx >= 0) ? fullName.substring(idx + 1) : fullName;
        }

    }

    // -------------------------------------------------------------------------
    // Persisted result model (XStream-friendly + Jelly-friendly)

    public static class ApplyResult {

        public List<String> applied = new ArrayList<>();
        public List<ApplyFailure> failures = new ArrayList<>();

        // Jelly convenience (optional, but nice)
        public int getAppliedCount() {
            return applied != null ? applied.size() : 0;
        }

        public int getFailuresCount() {
            return failures != null ? failures.size() : 0;
        }

    }

    public static final class ApplyFailure {

        private final String fullName;
        private final String error;

        public ApplyFailure(String fullName, String error) {
            this.fullName = fullName;
            this.error = error;
        }

        public String getFullName() {
            return fullName;
        }

        public String getError() {
            return error;
        }

    }

}
