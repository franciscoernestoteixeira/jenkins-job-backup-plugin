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
import hudson.model.AbstractItem;
import hudson.model.ItemGroup;
import hudson.model.TopLevelItemDescriptor;
import jenkins.model.Jenkins;
import jenkins.model.ModifiableTopLevelItemGroup;

import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Applies (restores) Jenkins items from {@code config.xml} files extracted from an uploaded ZIP.
 *
 * <h2>What this class does</h2>
 * <ul>
 *   <li>Expands the user selection using "prefix" semantics (folder selection implies children).</li>
 *   <li>Orders the apply list by path depth (parents first) so folders are created before children.</li>
 *   <li>For each selected item:
 *     <ul>
 *       <li>If it exists, updates its configuration via {@link AbstractItem#updateByXml(StreamSource)}.</li>
 *       <li>If it does not exist, creates it using {@link ModifiableTopLevelItemGroup#createProjectFromXML(String, InputStream)}.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h2>Dependencies</h2>
 * Folder creation requires the CloudBees Folders plugin because Jenkins core does not provide a
 * generic folder type. This service uses {@link Folder.DescriptorImpl} to create missing folders.
 *
 * <h2>Security considerations</h2>
 * Jenkins job {@code config.xml} may contain secrets or reference credentials. This service assumes:
 * <ul>
 *   <li>The caller is properly authorized at the controller/action layer.</li>
 *   <li>The uploaded ZIP content is accepted as trusted input by an administrator.</li>
 * </ul>
 * This class does not perform authorization checks; it focuses on the apply mechanics.
 */
public class ApplyService {

    /**
     * Applies selected items from the uploaded ZIP.
     *
     * <h2>Selection semantics</h2>
     * Mirrors export/import UI semantics:
     * <ul>
     *   <li>If selection contains {@code "A/B"}, treat it as a prefix and apply every entry in
     *       {@code configByFullName} whose key equals {@code "A/B"} or starts with {@code "A/B/"}.</li>
     *   <li>This is resilient when the UI selects folders, and when older ZIPs do not include
     *       synthetic folder rows in a preview.</li>
     * </ul>
     *
     * <h2>Apply order</h2>
     * Items are applied in ascending "depth" order (folders first, then jobs) so parent folders exist
     * before attempting to create/update deeper items.
     *
     * @param fullNames user selection as Jenkins {@code fullName} values (e.g., {@code "Folder/Job"}).
     *                 The values are treated as prefixes during expansion.
     * @param configByFullName map of Jenkins {@code fullName} to a filesystem {@link Path} pointing to
     *                         the extracted {@code config.xml} for that item.
     * @return an {@link Model.ApplyResult} containing applied item names and per-item failures.
     */
    public Model.ApplyResult apply(List<String> fullNames, Map<String, Path> configByFullName) {
        // Aggregate result; callers typically render this in the UI.
        var r = new Model.ApplyResult();

        // Defensive: if the ZIP did not yield any config.xml entries, there is nothing meaningful to do.
        if (configByFullName == null || configByFullName.isEmpty()) {
            r.failures.add(new Model.ApplyFailure("(zip)", "No config.xml entries found in uploaded ZIP"));
            return r;
        }

        // Expand the UI selection using prefix semantics and order it so parents are applied first.
        List<String> toApply = expandSelection(fullNames, configByFullName);

        // Apply each target independently. Failures are captured per item and do not stop the loop.
        for (var fullName : toApply) {
            var cfg = configByFullName.get(fullName);

            if (cfg == null) {
                // Should not occur after expandSelection(), but keep the operation safe and diagnosable.
                r.failures.add(new Model.ApplyFailure(fullName, "Missing config.xml in uploaded ZIP"));
                continue;
            }

            // Open the extracted config.xml and apply it.
            // try-with-resources ensures the stream is closed even if Jenkins throws.
            try (var in = Files.newInputStream(cfg)) {
                applyOne(fullName, in);
                r.applied.add(fullName);
            } catch (Exception e) {
                // Surface a concise, user-visible error while keeping the UI responsive.
                // Controller layer can log full stack traces if needed.
                r.failures.add(new Model.ApplyFailure(fullName, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        return r;
    }

    /**
     * Expands a UI selection list into concrete apply targets, using "prefix" semantics.
     *
     * <p>Example:</p>
     * <ul>
     *   <li>Selection: {@code ["A/B"]}</li>
     *   <li>ZIP keys: {@code ["A/B", "A/B/C", "A/B/C/job1", "X/Y"]}</li>
     *   <li>Expanded result includes {@code "A/B"} and all keys under {@code "A/B/"}.</li>
     * </ul>
     *
     * <p>Returned list is ordered by depth (parents first) and then by case-insensitive name to keep
     * execution deterministic.</p>
     *
     * @param selected the raw UI selection (may be null/empty, may contain blanks or duplicates).
     * @param configByFullName the keys available in the extracted ZIP (actual apply candidates).
     * @return ordered list of config keys to apply.
     */
    private List<String> expandSelection(List<String> selected, Map<String, Path> configByFullName) {
        // Normalize user selection:
        // - null-safe
        // - trim whitespace
        // - drop blanks
        // - de-duplicate while preserving encounter order
        List<String> sel = selected == null ? List.of() :
                selected.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .distinct()
                        .toList();

        // If user selected nothing, there is nothing to apply.
        // (Typically the controller prevents this, but keep the service robust.)
        if (sel.isEmpty()) {
            return List.of();
        }

        // Extract the keys present in the ZIP. These are the only valid apply targets.
        List<String> keys = new ArrayList<>(configByFullName.keySet());

        // Expand selection: include any key that exactly matches the selection or lies under it.
        // LinkedHashSet preserves insertion order, which helps with determinism before we sort.
        Set<String> expanded = new LinkedHashSet<>();

        for (String s : sel) {
            // Ensure we match children only as a path prefix:
            // - If selection is "A/B", then child keys begin with "A/B/".
            // - If selection already ends with "/", avoid double slashes.
            final String prefix = s.endsWith("/") ? s : (s + "/");

            for (String k : keys) {
                if (k.equals(s) || k.startsWith(prefix)) {
                    expanded.add(k);
                }
            }
        }

        // Sort so that:
        // 1) parents (smaller depth) are applied before children
        // 2) ties are deterministic (case-insensitive comparison)
        return expanded.stream()
                .sorted(Comparator
                        .comparingInt((String k) -> depthOf(k))
                        .thenComparing(String::compareToIgnoreCase))
                .collect(Collectors.toList());
    }

    /**
     * Computes the "depth" of a Jenkins {@code fullName} path.
     *
     * <p>Depth is the number of {@code '/'} separators:
     * <ul>
     *   <li>{@code "job"} => 0</li>
     *   <li>{@code "A/job"} => 1</li>
     *   <li>{@code "A/B/job"} => 2</li>
     * </ul>
     *
     * This is used to ensure parent folders are created/updated before deeper items.
     *
     * @param fullName Jenkins full name (folder/job path). May be null/blank.
     * @return number of path separators, or 0 for null/blank.
     */
    private int depthOf(String fullName) {
        if (fullName == null || fullName.isBlank()) return 0;

        int depth = 0;
        for (int i = 0; i < fullName.length(); i++) {
            if (fullName.charAt(i) == '/') depth++;
        }
        return depth;
    }

    /**
     * Applies a single item configuration represented by a {@code config.xml} stream.
     *
     * <h2>Behavior</h2>
     * <ul>
     *   <li>If the item already exists:
     *     <ul>
     *       <li>It must be an {@link AbstractItem} to support {@code updateByXml}.</li>
     *       <li>The item is updated in-place and saved.</li>
     *     </ul>
     *   </li>
     *   <li>If the item does not exist:
     *     <ul>
     *       <li>Ensures the parent folder path exists, creating folders as needed.</li>
     *       <li>Creates the item from XML under the resolved parent.</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p>Note: Jenkins reads the XML from the provided {@link InputStream}. The caller owns opening
     * and closing the stream.</p>
     *
     * @param fullName Jenkins item {@code fullName} (e.g., {@code "Folder/SubFolder/Job"}).
     * @param configXml input stream for the item's {@code config.xml}.
     * @throws Exception when Jenkins rejects the XML, folder creation fails, or the parent is not modifiable.
     */
    private void applyOne(String fullName, InputStream configXml) throws Exception {
        // Jenkins singleton; throws if Jenkins is not fully initialized.
        var j = Jenkins.get();

        // Split into parent path segments + leaf name.
        // Example: "A/B/job" -> ["A","B","job"]
        var parts = fullName.split("/");
        if (parts.length == 0) {
            throw new IllegalArgumentException("Invalid fullName: " + fullName);
        }

        // Default parent is Jenkins root (top-level item group).
        ItemGroup<?> parent = j;

        // If there are path segments before the leaf, ensure those folders exist.
        if (parts.length > 1) {
            parent = ensureFolderPath(parts, parts.length - 1);
        }

        // Leaf name is the actual item name to create under the resolved parent.
        var leafName = parts[parts.length - 1];

        // Check if the item already exists (any type).
        var existing = j.getItemByFullName(fullName);

        if (existing != null) {
            // Updating existing items requires AbstractItem.
            // Many Jenkins items derive from AbstractItem, but not all are safely updatable from XML.
            if (existing instanceof AbstractItem ai) {
                ai.updateByXml(new StreamSource(configXml));
                ai.save();
                return;
            }

            // Fail explicitly if Jenkins returns some other type.
            throw new IllegalStateException("Existing item is not updatable via config.xml: " + existing.getClass());
        }

        // Create a new item from the provided config.xml.
        // Parent must support creation from XML; Jenkins uses ModifiableTopLevelItemGroup for this.
        if (parent instanceof ModifiableTopLevelItemGroup mtlig) {
            mtlig.createProjectFromXML(leafName, configXml);
            return;
        }

        // If we cannot create under this parent, the path/structure is not compatible with this restore mechanism.
        throw new IllegalStateException("Parent is not a modifiable item group: " + parent.getClass());
    }

    /**
     * Ensures that all folder segments up to {@code folderDepthExclusive} exist, creating them if missing.
     *
     * <p>For a path like {@code ["A","B","job"]} and {@code folderDepthExclusive=2}, this ensures:
     * {@code A} and {@code A/B} exist as folders, then returns the {@code ItemGroup} for {@code A/B}.</p>
     *
     * <h2>Creation strategy</h2>
     * <ul>
     *   <li>Folder creation uses the CloudBees Folders plugin descriptor.</li>
     *   <li>Creation happens under the current {@link ItemGroup}, which must be modifiable.</li>
     *   <li>Each created folder is saved immediately.</li>
     * </ul>
     *
     * @param parts split {@code fullName} path segments (folders + leaf item).
     * @param folderDepthExclusive number of leading segments that represent folders
     *                             (i.e., index of the leaf item).
     * @return the deepest existing/created folder {@link ItemGroup} that should contain the leaf item.
     * @throws Exception if a folder cannot be created, if a segment exists but is not a folder,
     *                   or if required plugin descriptors are missing.
     */
    private ItemGroup<?> ensureFolderPath(String[] parts, int folderDepthExclusive) throws Exception {
        // Start at Jenkins root and walk each folder segment.
        ItemGroup<?> current = Jenkins.get();

        // Descriptor used to create folders. Fail fast if unavailable.
        var folderDescriptor = folderDescriptor();

        for (int i = 0; i < folderDepthExclusive; i++) {
            var folderName = parts[i];

            // Build the "current full name" for this segment: "A", then "A/B", etc.
            var currentFull = String.join("/", Arrays.copyOfRange(parts, 0, i + 1));

            // Look up any existing item at this path.
            var existing = Jenkins.get().getItemByFullName(currentFull);

            if (existing == null) {
                // We need to create this folder under the current parent.
                if (!(current instanceof ModifiableTopLevelItemGroup mtlig)) {
                    throw new IllegalStateException("Cannot create folder under: " + current.getClass());
                }

                // Create a folder item. The last parameter indicates "from scratch" vs copying.
                var created = mtlig.createProject(folderDescriptor, folderName, true);

                // Defensive: even if we requested a folder, ensure Jenkins gave us a Folder instance.
                if (!(created instanceof Folder f)) {
                    throw new IllegalStateException("Created item is not a Folder: " + created.getClass());
                }

                // Persist folder state (config.xml and other metadata).
                f.save();

                // Continue traversal from the created folder.
                current = f;
                continue;
            }

            // If the segment exists, it must be a folder to continue.
            if (existing instanceof Folder f) {
                current = f;
                continue;
            }

            // Existing item blocks folder path creation (e.g., a job named "A" where we need folder "A").
            throw new IllegalStateException("Path segment exists but is not a folder: " + currentFull);
        }

        return current;
    }

    /**
     * Resolves the {@link TopLevelItemDescriptor} required to create folders.
     *
     * <p>Jenkins core does not provide a general folder descriptor. The CloudBees Folders plugin
     * contributes {@link Folder.DescriptorImpl}. This service uses that descriptor to create folders
     * when restoring items into nested paths.</p>
     *
     * @return the folder descriptor used by Jenkins to instantiate folder items.
     * @throws IllegalStateException if the folders plugin is not installed/enabled.
     */
    private TopLevelItemDescriptor folderDescriptor() {
        var d = Jenkins.get().getDescriptorByType(Folder.DescriptorImpl.class);

        if (d == null) {
            throw new IllegalStateException(
                    "CloudBees Folders plugin is required to create folders (descriptor not found)."
            );
        }

        return d;
    }

}
