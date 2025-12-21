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
 *
 * <h2>Purpose</h2>
 * This class centralizes UI-related modeling and labeling so both screens display:
 * <ul>
 *   <li>Consistent human-readable job type labels (e.g., "Pipeline" instead of implementation class names)</li>
 *   <li>Consistent icon symbols based on those labels</li>
 *   <li>Common tree rendering helpers (parent, depth, leaf name)</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>This is a "pure model" class: no Jenkins mutations happen here.</li>
 *   <li>Nested classes are designed to be Jelly-friendly (JavaBean getters, simple fields).</li>
 *   <li>Import uses config.xml root-element detection to avoid mislabeling jobs as folders.</li>
 * </ul>
 */
public final class Model {

    /**
     * Utility-only class; prevent instantiation.
     */
    private Model() {
    }

    // -------------------------------------------------------------------------
    // Shared labeling helpers (keep Export/Import consistent)

    /**
     * Stable, human-readable labels used throughout the UI.
     *
     * <p>These values are the "source of truth" for type display. Both Export (runtime item mapping)
     * and Import (config.xml mapping) resolve into these labels.</p>
     */
    private static final String LABEL_FOLDER = "Folder";
    private static final String LABEL_PIPELINE = "Pipeline";
    private static final String LABEL_FREESTYLE = "Freestyle";
    private static final String LABEL_MAVEN = "Maven";
    private static final String LABEL_MULTIBRANCH_PIPELINE = "Multibranch Pipeline";
    private static final String LABEL_ORG_FOLDER = "Organization Folder";
    private static final String LABEL_JOB = "Job";

    /**
     * Maps a config.xml root element to a stable UI label.
     *
     * <p>This method is the "source of truth" for Import labeling because import candidates are
     * discovered from extracted {@code config.xml} files rather than live Jenkins item instances.</p>
     *
     * <p>Export (runtime items) maps to the same label set so that the user sees consistent strings
     * across both screens, regardless of plugin implementation class names.</p>
     *
     * @param rootElement the local name of the first XML start element in config.xml (may be blank).
     * @param isFolder whether this candidate is known to be a folder.
     * @return stable, user-friendly label to show in the UI.
     */
    private static String typeLabelFromRootElement(String rootElement, boolean isFolder) {
        // Folder detection overrides all other labeling: folders are always "Folder".
        if (isFolder) {
            return LABEL_FOLDER;
        }

        // Normalize root element for matching.
        String r = (rootElement == null) ? "" : rootElement.trim();
        if (r.isEmpty()) {
            // Unknown root element: treat as generic job.
            return LABEL_JOB;
        }

        // Known Jenkins core/plugin roots that are commonly used as XML root elements.
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

        // Some plugins use fully qualified class names as the root element.
        String normalized = r.toLowerCase(Locale.ROOT);

        // Multibranch pipeline variants.
        if ("org.jenkinsci.plugins.workflow.multibranch.workflowmultibranchproject".equals(normalized)
                || normalized.endsWith(".workflowmultibranchproject")) {
            return LABEL_MULTIBRANCH_PIPELINE;
        }

        // Organization folder variants.
        if ("jenkins.branch.organizationfolder".equals(normalized)
                || normalized.endsWith(".organizationfolder")) {
            return LABEL_ORG_FOLDER;
        }

        // Fallback: provide a readable value without exposing long Java packages in the UI.
        return simplifyRootElement(r);
    }

    /**
     * Maps a runtime Jenkins {@link Item} to the same stable UI labels used by Import.
     *
     * <p>Jenkins implementation class names (e.g., {@code WorkflowJob}) are not ideal UI strings.
     * This method detects common item types via FQCN checks and translates them into stable labels.</p>
     *
     * <p>Using FQCN string comparison avoids a hard compile-time dependency on optional plugins
     * (workflow-job, maven-plugin, folders, branch-api, etc.).</p>
     *
     * @param item Jenkins item instance (may be null).
     * @param isFolder whether this item is known to be a folder.
     * @return stable, user-friendly type label.
     */
    private static String typeLabelFromItem(Item item, boolean isFolder) {
        if (item == null) {
            return LABEL_JOB;
        }

        if (isFolder) {
            return LABEL_FOLDER;
        }

        // Pipeline job (workflow-job plugin).
        if (isInstanceOf(item, "org.jenkinsci.plugins.workflow.job.WorkflowJob")) {
            return LABEL_PIPELINE;
        }

        // Freestyle (Jenkins core).
        if (isInstanceOf(item, "hudson.model.FreeStyleProject")) {
            return LABEL_FREESTYLE;
        }

        // Maven (maven-plugin).
        if (isInstanceOf(item, "hudson.maven.MavenModuleSet")) {
            return LABEL_MAVEN;
        }

        // Multibranch pipeline (workflow-multibranch plugin).
        if (isInstanceOf(item, "org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject")) {
            return LABEL_MULTIBRANCH_PIPELINE;
        }

        // Organization folder (branch-api plugin).
        if (isInstanceOf(item, "jenkins.branch.OrganizationFolder")) {
            return LABEL_ORG_FOLDER;
        }

        // Fallback: show simple class name. This mirrors import fallback behavior (simplified root element).
        return item.getClass().getSimpleName();
    }

    /**
     * Maps a stable UI label to an Ionicons symbol identifier.
     *
     * <p>These symbols are used by Jelly templates via {@code <l:icon src="..."/>}.</p>
     *
     * <p>Folder detection takes precedence: folders always map to the folder icon.</p>
     *
     * @param typeLabel stable UI label (e.g., "Pipeline").
     * @param isFolder whether the underlying row represents a folder node.
     * @return a Jenkins icon "src" value for Ionicons.
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
                // Generic fallback for unknown/non-specialized job types.
                return "symbol-document-text-outline";
        }
    }

    /**
     * Checks if an object is an instance of a class identified by its fully qualified class name (FQCN).
     *
     * <p>This method is used to avoid compile-time dependencies on optional plugins. For example,
     * the plugin can label workflow jobs as "Pipeline" even if the workflow-job plugin is not on the
     * compile classpath, as long as the runtime class name matches.</p>
     *
     * <p>It checks both:</p>
     * <ul>
     *   <li>the superclass chain</li>
     *   <li>the directly-implemented interfaces</li>
     * </ul>
     *
     * @param o the object to test.
     * @param fqcn fully qualified class name to check.
     * @return true if {@code o} is an instance of the class with name {@code fqcn}.
     */
    private static boolean isInstanceOf(Object o, String fqcn) {
        if (o == null || fqcn == null || fqcn.isBlank()) {
            return false;
        }

        // Walk superclass chain.
        Class<?> c = o.getClass();
        while (c != null) {
            if (fqcn.equals(c.getName())) {
                return true;
            }
            c = c.getSuperclass();
        }

        // Check directly-implemented interfaces.
        // (This is usually sufficient for Jenkins item types.)
        for (Class<?> i : o.getClass().getInterfaces()) {
            if (fqcn.equals(i.getName())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Simplifies a root element that may be a fully qualified class name into a readable label.
     *
     * <p>Example: {@code "com.example.SomeJobType"} becomes {@code "SomeJobType"}.</p>
     *
     * @param rootElement root element string.
     * @return simplified value, or {@link #LABEL_JOB} if blank.
     */
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

    /**
     * Returns the parent full name for a given Jenkins full name.
     *
     * <p>Example: {@code "A/B/job"} -> {@code "A/B"}.</p>
     *
     * @param fullName Jenkins item full name.
     * @return parent full name, or null if the item is top-level or input is null/invalid.
     */
    private static String parentOf(String fullName) {
        if (fullName == null) return null;
        int idx = fullName.lastIndexOf('/');
        if (idx <= 0) return null;
        return fullName.substring(0, idx);
    }

    // -------------------------------------------------------------------------
    // View models (Jelly-friendly: JavaBean getters)

    /**
     * A row representing a Jenkins item in the Export screen.
     *
     * <p>Designed for Jelly rendering:</p>
     * <ul>
     *   <li>Simple immutable fields</li>
     *   <li>JavaBean getter methods</li>
     *   <li>Convenience methods for icon and tree rendering</li>
     * </ul>
     */
    public static final class ItemRow {

        /**
         * Jenkins full name (folder/job path).
         */
        private final String fullName;

        /**
         * Parent full name (null if this item is top-level).
         */
        private final String parentFullName;

        /**
         * A user-friendly type label used by the UI.
         *
         * <p>This intentionally mirrors the import preview labeling
         * (e.g., "Pipeline" instead of "WorkflowJob").</p>
         */
        private final String type;

        /**
         * True if this row represents a folder node.
         */
        private final boolean folder;

        /**
         * Creates an export row.
         *
         * @param fullName Jenkins full name.
         * @param parentFullName parent full name, or null for root.
         * @param type stable UI type label.
         * @param folder whether this row is a folder.
         */
        public ItemRow(String fullName, String parentFullName, String type, boolean folder) {
            this.fullName = fullName;
            this.parentFullName = parentFullName;
            this.type = type;
            this.folder = folder;
        }

        /**
         * @return Jenkins full name.
         */
        public String getFullName() {
            return fullName;
        }

        /**
         * @return parent full name, or null if top-level.
         */
        public String getParentFullName() {
            return parentFullName;
        }

        /**
         * @return stable UI label (e.g., "Pipeline", "Freestyle").
         */
        public String getType() {
            return type;
        }

        /**
         * @return true if this item is a folder node.
         */
        public boolean isFolder() {
            return folder;
        }

        /**
         * Icon symbol used by the export view (Jelly).
         *
         * @return Ionicons symbol identifier appropriate for this row.
         */
        public String getIconSymbol() {
            return iconSymbolFromTypeLabel(type, folder);
        }

        /**
         * Factory for a synthetic folder row.
         *
         * <p>Export UI can include folder nodes even if they do not correspond to concrete items,
         * in order to show a consistent tree structure.</p>
         *
         * @param fullName folder path.
         * @return folder row.
         */
        public static ItemRow folder(String fullName) {
            return new ItemRow(
                    fullName,
                    parentOf(fullName),
                    LABEL_FOLDER,
                    true
            );
        }

        /**
         * Factory for a row backed by a runtime Jenkins {@link Item}.
         *
         * @param item Jenkins item instance.
         * @return row with stable type label and folder detection.
         */
        public static ItemRow from(Item item) {
            String fullName = item.getFullName();

            // If the folders plugin is present, this is accurate. If not present, it will simply be false.
            boolean isFolder = item instanceof com.cloudbees.hudson.plugins.folder.Folder;

            return new ItemRow(
                    fullName,
                    parentOf(fullName),
                    typeLabelFromItem(item, isFolder),
                    isFolder
            );
        }

        /**
         * Computes the depth of this row in the tree (number of '/' separators).
         *
         * @return depth >= 0.
         */
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

        /**
         * Returns the last segment of the full name.
         *
         * <p>Example: {@code "A/B/job"} -> {@code "job"}.</p>
         *
         * @return leaf name, never null.
         */
        public String getLeafName() {
            if (fullName == null) return "";
            int idx = fullName.lastIndexOf('/');
            return (idx >= 0) ? fullName.substring(idx + 1) : fullName;
        }
    }

    /**
     * A row representing a discovered item in the Import preview screen.
     *
     * <p>Unlike export, import candidates come from the extracted ZIP file structure and {@code config.xml}
     * content. This class stores both the derived metadata and UI-facing helpers.</p>
     */
    public static final class ImportCandidate {

        /**
         * Jenkins full name derived from ZIP directory structure.
         */
        private final String fullName;

        /**
         * Parent full name (null when top-level).
         */
        private final String parentFullName;

        /**
         * String path to config.xml on disk (kept as String to simplify Jelly binding).
         */
        private final String configXmlPath;

        /**
         * Whether an item with this full name already exists in the target Jenkins.
         */
        private final boolean exists;

        /**
         * True if this candidate represents a folder node.
         *
         * <p>For synthetic folder nodes (created purely for tree display), this will also be true.</p>
         */
        private final boolean folder;

        /**
         * The root XML element from config.xml (e.g. "flow-definition", "project",
         * "com.cloudbees.hudson.plugins.folder.Folder").
         *
         * <p>This is used to derive stable UI labels and as a debugging hint.</p>
         */
        private final String rootElement;

        /**
         * Creates an import candidate.
         *
         * @param fullName Jenkins full name.
         * @param parentFullName parent full name.
         * @param configXmlPath filesystem path to config.xml (may be blank for synthetic folders).
         * @param exists whether this item exists in current Jenkins.
         * @param folder folder flag.
         * @param rootElement parsed config.xml root element.
         */
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
         * <p>This is derived from the config.xml root element and matches Export's labeling.</p>
         *
         * @return stable type label for display.
         */
        public String getTypeLabel() {
            return typeLabelFromRootElement(rootElement, folder);
        }

        /**
         * Icon symbol used by the Jelly views.
         *
         * <p>Folder detection is the critical part; non-folder jobs can use a generic icon based on label.</p>
         *
         * @return Ionicons symbol identifier appropriate for this candidate.
         */
        public String getIconSymbol() {
            return iconSymbolFromTypeLabel(getTypeLabel(), folder);
        }

        /**
         * Factory for a (possibly synthetic) folder node in the import tree.
         *
         * <p>Synthetic folders are nodes created to render a full tree even if the ZIP does not contain
         * a real folder item config for the path. Those nodes have no config.xml path.</p>
         *
         * @param fullName folder full name.
         * @param exists whether a real Jenkins item exists for this folder path.
         * @return import candidate representing a folder node.
         */
        public static ImportCandidate folder(String fullName, boolean exists) {
            return new ImportCandidate(
                    fullName,
                    parentOf(fullName),
                    "",          // No config path for synthetic folder nodes.
                    exists,
                    true,
                    "synthetic-folder"
            );
        }

        /**
         * Factory for an import candidate derived from a config.xml file.
         *
         * @param fullName Jenkins full name derived from ZIP.
         * @param configXmlPath path to config.xml on disk (string form).
         * @param exists whether a Jenkins item already exists at this path.
         * @return import candidate with detected metadata.
         */
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

        /**
         * Holder for detection results produced from config.xml parsing.
         */
        private static final class Detection {
            final boolean folder;
            final String rootElement;

            private Detection(boolean folder, String rootElement) {
                this.folder = folder;
                this.rootElement = rootElement;
            }
        }

        /**
         * Detects folder-vs-job and extracts the root element from a config.xml file.
         *
         * <h2>Why root element detection</h2>
         * Path-based heuristics are unreliable: every Jenkins item (job or folder) uses {@code config.xml}.
         * Reading the root element is a cheap and robust way to infer the item family.
         *
         * <h2>Security hardening</h2>
         * The parser uses StAX and disables:
         * <ul>
         *   <li>DTDs</li>
         *   <li>external entity expansion</li>
         * </ul>
         * to reduce the risk of XXE and related XML-based attacks.
         *
         * <h2>Failure behavior</h2>
         * If parsing fails, the method defaults to "not a folder" so the UI avoids false positives.
         *
         * @param configXmlPath string path to config.xml.
         * @return detection result containing folder flag and root element (possibly empty).
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

                // Hardening: prevent XXE / external entity expansion.
                // Some StAX implementations may throw IllegalArgumentException for unknown properties,
                // so each property set is individually guarded.
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

            // CloudBees Folder plugin is the most common folder type.
            // Root element for folders often appears as a FQCN in XML (case varies by serializer).
            boolean isFolder = "com.cloudbees.hudson.plugins.folder.folder".equals(normalized)
                    || normalized.endsWith(".folder");

            return new Detection(isFolder, root == null ? "" : root);
        }

        /**
         * Computes tree depth of this candidate (number of '/' separators).
         *
         * @return depth >= 0.
         */
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

        /**
         * Returns the last segment of the full name.
         *
         * @return leaf name, never null.
         */
        public String getLeafName() {
            if (fullName == null) return "";
            int idx = fullName.lastIndexOf('/');
            return (idx >= 0) ? fullName.substring(idx + 1) : fullName;
        }
    }

    // -------------------------------------------------------------------------
    // Persisted result model (XStream-friendly + Jelly-friendly)

    /**
     * Result of applying selected import items.
     *
     * <p>This class is persisted to disk using Jenkins {@code XmlFile} (XStream-based serialization)
     * and is also used directly by Jelly for rendering the result summary.</p>
     *
     * <p>Fields are intentionally public to remain XStream-friendly without requiring setters.</p>
     */
    public static class ApplyResult {

        /**
         * Full names that were successfully applied (created or updated).
         */
        public List<String> applied = new ArrayList<>();

        /**
         * Per-item failures captured during apply.
         */
        public List<ApplyFailure> failures = new ArrayList<>();

        /**
         * Convenience getter for Jelly rendering.
         *
         * @return number of successfully applied items.
         */
        public int getAppliedCount() {
            return applied != null ? applied.size() : 0;
        }

        /**
         * Convenience getter for Jelly rendering.
         *
         * @return number of failures.
         */
        public int getFailuresCount() {
            return failures != null ? failures.size() : 0;
        }
    }

    /**
     * Represents a single apply failure with a user-friendly error message.
     */
    public static final class ApplyFailure {

        /**
         * Jenkins full name for the item that failed (or special token like "(zip)").
         */
        private final String fullName;

        /**
         * User-facing error message (kept concise for UI display).
         */
        private final String error;

        /**
         * Creates a failure record.
         *
         * @param fullName Jenkins full name for the failed item.
         * @param error concise error message.
         */
        public ApplyFailure(String fullName, String error) {
            this.fullName = fullName;
            this.error = error;
        }

        /**
         * @return Jenkins full name for the failed item.
         */
        public String getFullName() {
            return fullName;
        }

        /**
         * @return user-facing error message.
         */
        public String getError() {
            return error;
        }
    }

}
