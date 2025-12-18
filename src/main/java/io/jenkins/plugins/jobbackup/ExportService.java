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

public class ExportService {

    /**
     * Streams a ZIP containing config.xml for all selected items.
     * <p>
     * Selection semantics:
     * - If fullName points to a real item: export it (if AbstractItem-backed).
     * - If fullName points to a folder OR a synthetic folder path: export ALL AbstractItems whose
     * fullName == fullName OR startsWith(fullName + "/").
     * <p>
     * This supports selecting "folders" even when the folder node is synthetic (ancestor) and does not
     * exist as a real Jenkins item.
     */
    public void streamZip(List<String> fullNames, OutputStream out) throws IOException {
        var j = Jenkins.get();

        // Collect all export targets (AbstractItems) without duplicates, stable order.
        List<AbstractItem> targets = expandToAbstractItems(j, fullNames);

        try (var zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (var ai : targets) {
                var fullName = ai.getFullName();

                var entryName = sanitizeZipPath(fullName) + "/config.xml";
                zos.putNextEntry(new ZipEntry(entryName));

                try (InputStream in = ai.getConfigFile().getFile().toPath().toUri().toURL().openStream()) {
                    in.transferTo(zos);
                }

                zos.closeEntry();
            }
        }
    }

    private List<AbstractItem> expandToAbstractItems(Jenkins j, List<String> selectedFullNames) {
        // We scan all AbstractItems once and match by equality/prefix.
        // This covers: jobs, multibranch, folders (Folder is also an AbstractItem), etc.
        List<AbstractItem> all = j.getAllItems(AbstractItem.class);

        // Normalize selection (trim, remove empty), keep insertion order
        List<String> selected = selectedFullNames == null ? List.of() :
                selectedFullNames.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .distinct()
                        .toList();

        // Fast path: nothing selected
        if (selected.isEmpty()) {
            return List.of();
        }

        // For stable output and easy apply later:
        // sort by depth then name
        all.sort(Comparator
                .comparingInt((AbstractItem i) -> depthOf(i.getFullName()))
                .thenComparing(AbstractItem::getFullName, String.CASE_INSENSITIVE_ORDER));

        // Deduplicate while preserving order
        Map<String, AbstractItem> result = new LinkedHashMap<>();

        for (String sel : selected) {
            final String prefix = sel.endsWith("/") ? sel : (sel + "/");

            for (AbstractItem ai : all) {
                String name = ai.getFullName();

                if (name.equals(sel) || name.startsWith(prefix)) {
                    result.putIfAbsent(name, ai);
                }
            }
        }

        return new ArrayList<>(result.values());
    }

    private int depthOf(String fullName) {
        if (fullName == null || fullName.isBlank()) return 0;
        int depth = 0;
        for (int i = 0; i < fullName.length(); i++) {
            if (fullName.charAt(i) == '/') depth++;
        }
        return depth;
    }

    private String sanitizeZipPath(String fullName) {
        // Jenkins fullName uses '/' for folders; keep it, but block weirdness.
        var s = (fullName == null ? "" : fullName).replace('\\', '/');

        while (s.startsWith("/")) {
            s = s.substring(1);
        }

        return s;
    }

}
