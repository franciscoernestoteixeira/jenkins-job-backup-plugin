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

public class ApplyService {

    /**
     * Applies selected items from the uploaded ZIP.
     * <p>
     * Selection semantics (mirrors export):
     * - If selection includes "A/B" treat it as a prefix and apply every configByFullName entry
     * whose key == "A/B" OR startsWith("A/B/").
     * - This makes apply robust even if the UI sends only folders or if older zips lack synthetic
     * folder rows in the preview.
     * <p>
     * Apply order:
     * - by depth ascending: folders first, then deeper items (jobs), to ensure parent folders exist.
     */
    public Model.ApplyResult apply(List<String> fullNames, Map<String, Path> configByFullName) {
        var r = new Model.ApplyResult();

        if (configByFullName == null || configByFullName.isEmpty()) {
            r.failures.add(new Model.ApplyFailure("(zip)", "No config.xml entries found in uploaded ZIP"));
            return r;
        }

        List<String> toApply = expandSelection(fullNames, configByFullName);

        for (var fullName : toApply) {
            var cfg = configByFullName.get(fullName);

            if (cfg == null) {
                // Should not happen after expandSelection, but keep safe
                r.failures.add(new Model.ApplyFailure(fullName, "Missing config.xml in uploaded ZIP"));
                continue;
            }

            try (var in = Files.newInputStream(cfg)) {
                applyOne(fullName, in);
                r.applied.add(fullName);
            } catch (Exception e) {
                r.failures.add(new Model.ApplyFailure(fullName, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        return r;
    }

    private List<String> expandSelection(List<String> selected, Map<String, Path> configByFullName) {
        // Normalize selection
        List<String> sel = selected == null ? List.of() :
                selected.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .distinct()
                        .toList();

        // If user selected nothing (should be blocked by controller), return empty
        if (sel.isEmpty()) {
            return List.of();
        }

        // Keys available in ZIP (these are the real apply targets)
        List<String> keys = new ArrayList<>(configByFullName.keySet());

        // Expand: include any key that equals selection or is under selection prefix
        Set<String> expanded = new LinkedHashSet<>();

        for (String s : sel) {
            final String prefix = s.endsWith("/") ? s : (s + "/");

            for (String k : keys) {
                if (k.equals(s) || k.startsWith(prefix)) {
                    expanded.add(k);
                }
            }
        }

        // Apply in depth order (parents first), then name
        return expanded.stream()
                .sorted(Comparator
                        .comparingInt((String k) -> depthOf(k))
                        .thenComparing(String::compareToIgnoreCase))
                .collect(Collectors.toList());
    }

    private int depthOf(String fullName) {
        if (fullName == null || fullName.isBlank()) return 0;
        int depth = 0;
        for (int i = 0; i < fullName.length(); i++) {
            if (fullName.charAt(i) == '/') depth++;
        }
        return depth;
    }

    private void applyOne(String fullName, InputStream configXml) throws Exception {
        var j = Jenkins.get();

        // Split into parent path + leaf name
        var parts = fullName.split("/");
        if (parts.length == 0) {
            throw new IllegalArgumentException("Invalid fullName: " + fullName);
        }

        ItemGroup<?> parent = j;

        if (parts.length > 1) {
            parent = ensureFolderPath(parts, parts.length - 1);
        }

        var leafName = parts[parts.length - 1];
        var existing = j.getItemByFullName(fullName);

        if (existing != null) {
            if (existing instanceof AbstractItem ai) {
                ai.updateByXml(new StreamSource(configXml));
                ai.save();
                return;
            }

            throw new IllegalStateException("Existing item is not updatable via config.xml: " + existing.getClass());
        }

        // Create
        if (parent instanceof ModifiableTopLevelItemGroup mtlig) {
            mtlig.createProjectFromXML(leafName, configXml);
            return;
        }

        throw new IllegalStateException("Parent is not a modifiable item group: " + parent.getClass());
    }

    private ItemGroup<?> ensureFolderPath(String[] parts, int folderDepthExclusive) throws Exception {
        ItemGroup<?> current = Jenkins.get();
        var folderDescriptor = folderDescriptor();

        for (int i = 0; i < folderDepthExclusive; i++) {
            var folderName = parts[i];
            var currentFull = String.join("/", Arrays.copyOfRange(parts, 0, i + 1));
            var existing = Jenkins.get().getItemByFullName(currentFull);

            if (existing == null) {
                if (!(current instanceof ModifiableTopLevelItemGroup mtlig)) {
                    throw new IllegalStateException("Cannot create folder under: " + current.getClass());
                }

                var created = mtlig.createProject(folderDescriptor, folderName, true);

                if (!(created instanceof Folder f)) {
                    throw new IllegalStateException("Created item is not a Folder: " + created.getClass());
                }

                f.save();
                current = f;
                continue;
            }

            if (existing instanceof Folder f) {
                current = f;
                continue;
            }

            throw new IllegalStateException("Path segment exists but is not a folder: " + currentFull);
        }

        return current;
    }

    private TopLevelItemDescriptor folderDescriptor() {
        var d = Jenkins.get().getDescriptorByType(Folder.DescriptorImpl.class);

        if (d == null) {
            throw new IllegalStateException("CloudBees Folders plugin is required to create folders (descriptor not found).");
        }

        return d;
    }

}
