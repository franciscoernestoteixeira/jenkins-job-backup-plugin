package io.jenkins.plugins.jobbackup;

import hudson.model.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Model {

    private Model() {
    }

    // -------------------------------------------------------------------------
    // View models (Jelly-friendly: JavaBean getters)

    public static final class ItemRow {

        private final String fullName;
        private final String parentFullName;   // null when root
        private final String type;             // "Folder" or Jenkins class simple name
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

        public static ItemRow folder(String fullName) {
            return new ItemRow(
                    fullName,
                    parentOf(fullName),
                    "Folder",
                    true
            );
        }

        public static ItemRow from(Item item) {
            String fullName = item.getFullName();
            boolean isFolder = item instanceof com.cloudbees.hudson.plugins.folder.Folder;

            return new ItemRow(
                    fullName,
                    parentOf(fullName),
                    item.getClass().getSimpleName(),
                    isFolder
            );
        }

        private static String parentOf(String fullName) {
            if (fullName == null) return null;
            int idx = fullName.lastIndexOf('/');
            if (idx <= 0) return null;
            return fullName.substring(0, idx);
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

        public ImportCandidate(String fullName,
                               String parentFullName,
                               String configXmlPath,
                               boolean exists,
                               boolean folder) {
            this.fullName = fullName;
            this.parentFullName = parentFullName;
            this.configXmlPath = configXmlPath;
            this.exists = exists;
            this.folder = folder;
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

        public static ImportCandidate folder(String fullName, boolean exists) {
            return new ImportCandidate(
                    fullName,
                    parentOf(fullName),
                    "",          // no config path for synthetic folder nodes
                    exists,
                    true
            );
        }

        public static ImportCandidate from(String fullName, String configXmlPath, boolean exists) {
            boolean folder = isFolderConfig(configXmlPath);

            return new ImportCandidate(
                    fullName,
                    parentOf(fullName),
                    configXmlPath,
                    exists,
                    folder
            );
        }

        private static String parentOf(String fullName) {
            if (fullName == null) return null;
            int idx = fullName.lastIndexOf('/');
            if (idx <= 0) return null;
            return fullName.substring(0, idx);
        }

        /**
         * Heuristic: in exported Jenkins items, folder nodes typically have a config.xml at the folder root.
         * If your ExportService always writes folders as ".../config.xml", you can detect folders here.
         * If not, you can override folder-ness in ImportSessionService candidate metadata instead.
         */
        private static boolean isFolderConfig(String configXmlPath) {
            if (configXmlPath == null) return false;
            String p = configXmlPath.replace('\\', '/').toLowerCase(Locale.ROOT);

            // Common patterns:
            // - folders/<name>/config.xml
            // - <fullName>/config.xml where <fullName> has slashes
            // This is intentionally conservative; folder rows will also be synthesized anyway.
            return p.endsWith("/config.xml") && p.contains("/");
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