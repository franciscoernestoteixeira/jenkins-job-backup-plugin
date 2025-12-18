package io.jenkins.plugins.jobpack;

import hudson.model.Item;

import java.util.ArrayList;
import java.util.List;

public final class Model {

    private Model() {
    }

    // -------------------------------------------------------------------------
    // View models (Jelly-friendly: JavaBean getters)

    public static final class ItemRow {

        private final String fullName;
        private final String type;

        public ItemRow(String fullName, String type) {
            this.fullName = fullName;
            this.type = type;
        }

        public String getFullName() {
            return fullName;
        }

        public String getType() {
            return type;
        }

        public static ItemRow from(Item item) {
            return new ItemRow(item.getFullName(), item.getClass().getSimpleName());
        }
    }

    public static final class ImportCandidate {

        private final String fullName;
        private final String configXmlPath;
        private final boolean exists;

        public ImportCandidate(String fullName, String configXmlPath, boolean exists) {
            this.fullName = fullName;
            this.configXmlPath = configXmlPath;
            this.exists = exists;
        }

        public String getFullName() {
            return fullName;
        }

        public String getConfigXmlPath() {
            return configXmlPath;
        }

        public boolean isExists() {
            return exists;
        }

        public static ImportCandidate from(String fullName, String configXmlPath, boolean exists) {
            return new ImportCandidate(fullName, configXmlPath, exists);
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