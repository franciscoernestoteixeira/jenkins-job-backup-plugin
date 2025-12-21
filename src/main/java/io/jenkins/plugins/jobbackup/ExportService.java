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

import hudson.model.AbstractItem;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports Jenkins job/folder configurations as a ZIP stream.
 *
 * <h2>ZIP layout</h2>
 * For each exported item, this service writes:
 * <pre>{@code
 * <fullName>/config.xml
 * }</pre>
 * where {@code <fullName>} is the Jenkins item full name (folder/job path).
 *
 * <h2>Selection semantics</h2>
 * The caller passes a list of "fullName" values (as shown in the UI).
 * This service expands each selection as follows:
 * <ul>
 *   <li>If {@code fullName} points to a real item, export it (only if it is an {@link AbstractItem}).</li>
 *   <li>If {@code fullName} points to a folder OR a synthetic folder path (a prefix that may not exist as
 *       a real item), export every {@link AbstractItem} whose full name equals the selection or starts with
 *       {@code fullName + "/"}.</li>
 * </ul>
 *
 * <p>This design supports selecting folders even when the UI includes "synthetic" ancestors that
 * are not present as actual {@code Folder} items in Jenkins.</p>
 *
 * <h2>Security considerations</h2>
 * Jenkins {@code config.xml} may contain secrets (depending on plugin configuration).
 * This class assumes the caller enforces appropriate Jenkins permissions at the controller/action layer.
 */
public class ExportService {

    /**
     * Streams a ZIP containing {@code config.xml} for all expanded targets derived from {@code fullNames}.
     *
     * <p>The ZIP is written directly to the provided {@link OutputStream}. The stream is not closed
     * by this method; only the {@link ZipOutputStream} wrapper is closed, which also finishes the ZIP
     * format (central directory) properly.</p>
     *
     * <p>Entries are written using UTF-8 so non-ASCII names remain portable.</p>
     *
     * @param fullNames list of Jenkins full names selected by the user (folder/job paths). May be null.
     * @param out destination stream (typically {@code StaplerResponse#getOutputStream()}).
     * @throws IOException if any I/O error occurs while writing the ZIP or reading config files.
     */
    public void streamZip(List<String> fullNames, OutputStream out) throws IOException {
        // Jenkins singleton used to query items.
        var j = Jenkins.get();

        // Expand the selection into concrete export targets (AbstractItems), de-duplicated and ordered.
        List<AbstractItem> targets = expandToAbstractItems(j, fullNames);

        // Create a ZIP stream using UTF-8 for entry names.
        // try-with-resources ensures ZIP finalization even if an item export fails.
        try (var zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (var ai : targets) {
                // Jenkins fullName is the canonical stable identifier for folders/jobs.
                var fullName = ai.getFullName();

                // ZIP entry name: "<fullName>/config.xml"
                // sanitizeZipPath defends against odd leading slashes and backslashes.
                var entryName = sanitizeZipPath(fullName) + "/config.xml";
                zos.putNextEntry(new ZipEntry(entryName));

                // Stream the on-disk config.xml into the ZIP.
                // Using URL.openStream() avoids assuming local filesystem access patterns, but still
                // works with the default ConfigFile implementation.
                try (InputStream in = ai.getConfigFile().getFile().toPath().toUri().toURL().openStream()) {
                    in.transferTo(zos);
                }

                // Close the current entry so the next one can be written.
                zos.closeEntry();
            }
        }
    }

    /**
     * Expands a list of selected full names into a concrete, ordered list of {@link AbstractItem} export targets.
     *
     * <h2>Why we scan all items</h2>
     * The UI may select:
     * <ul>
     *   <li>a real item full name (job or folder)</li>
     *   <li>or a synthetic folder path that exists only as a prefix</li>
     * </ul>
     * By scanning all {@link AbstractItem}s once and matching by equality or prefix, we cover both cases.
     *
     * <h2>Ordering</h2>
     * The returned list is stable and deterministic:
     * <ul>
     *   <li>Items are sorted by depth (parent folders first), then by name (case-insensitive).</li>
     *   <li>De-duplication preserves the first encounter order after sorting.</li>
     * </ul>
     *
     * @param j Jenkins instance used to fetch items.
     * @param selectedFullNames raw selection from UI; may be null, blank, contain duplicates.
     * @return ordered, de-duplicated list of export targets.
     */
    private List<AbstractItem> expandToAbstractItems(Jenkins j, List<String> selectedFullNames) {
        // Get every AbstractItem in Jenkins. This includes jobs, folders, multibranch, etc.
        // Note: Folder (from folders plugin) is an AbstractItem, which is desirable for export.
        List<AbstractItem> all = j.getAllItems(AbstractItem.class);

        // Normalize selection:
        // - null-safe
        // - trim whitespace
        // - drop blanks
        // - de-duplicate values
        List<String> selected = selectedFullNames == null ? List.of() :
                selectedFullNames.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .distinct()
                        .toList();

        // Nothing selected => nothing to export.
        if (selected.isEmpty()) {
            return List.of();
        }

        // Deterministic ordering helps:
        // - reproducible ZIP output
        // - predictable apply order
        // - consistent UI results when comparing ZIPs
        all.sort(Comparator
                .comparingInt((AbstractItem i) -> depthOf(i.getFullName()))
                .thenComparing(AbstractItem::getFullName, String.CASE_INSENSITIVE_ORDER));

        // Deduplicate targets while preserving the deterministic sorted order.
        // LinkedHashMap preserves insertion order and lets us key by full name.
        Map<String, AbstractItem> result = new LinkedHashMap<>();

        // For each selection token, include items that equal it or live under it as a prefix.
        for (String sel : selected) {
            // Ensure prefix comparison works consistently:
            // "A/B" matches children that start with "A/B/".
            final String prefix = sel.endsWith("/") ? sel : (sel + "/");

            for (AbstractItem ai : all) {
                String name = ai.getFullName();

                // Prefix semantics:
                // - exact match (export the item itself)
                // - descendant match (export everything under the selected path)
                if (name.equals(sel) || name.startsWith(prefix)) {
                    result.putIfAbsent(name, ai);
                }
            }
        }

        // Convert to list preserving insertion order.
        return new ArrayList<>(result.values());
    }

    /**
     * Computes the depth of a Jenkins full name path.
     *
     * <p>Depth is the number of {@code '/'} separators:</p>
     * <ul>
     *   <li>{@code "job"} => 0</li>
     *   <li>{@code "A/job"} => 1</li>
     *   <li>{@code "A/B/job"} => 2</li>
     * </ul>
     *
     * <p>This is used to sort items so parents come before children, making export output
     * consistent and friendlier for restore/apply flows.</p>
     *
     * @param fullName Jenkins full name (folder/job path). May be null/blank.
     * @return number of path separators, or 0 when null/blank.
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
     * Sanitizes a Jenkins full name for use as a ZIP entry path prefix.
     *
     * <p>Jenkins full names naturally use {@code '/'} as the folder separator, which is compatible
     * with ZIP paths. This method performs minimal sanitation:</p>
     * <ul>
     *   <li>Converts Windows backslashes ({@code '\'}) to {@code '/'}.</li>
     *   <li>Removes any leading {@code '/'} characters to avoid absolute-like paths inside the ZIP.</li>
     * </ul>
     *
     * <p>Note: This method intentionally does not aggressively rewrite names, because Jenkins item names
     * are expected to be valid and are used as stable identifiers for restore/apply.</p>
     *
     * @param fullName Jenkins item full name (may be null).
     * @return sanitized path prefix for ZIP entries (never null).
     */
    private String sanitizeZipPath(String fullName) {
        // Jenkins fullName uses '/' for folders; keep it, but normalize slashes to avoid surprises.
        var s = (fullName == null ? "" : fullName).replace('\\', '/');

        // Defensive: avoid entries that start with '/', which can be interpreted as absolute paths by some tools.
        while (s.startsWith("/")) {
            s = s.substring(1);
        }

        return s;
    }

}
