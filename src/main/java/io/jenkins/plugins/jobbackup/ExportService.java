package io.jenkins.plugins.jobbackup;

import hudson.model.AbstractItem;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ExportService {

    public void streamZip(List<String> fullNames, OutputStream out) throws IOException {
        try (var zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (var fullName : fullNames) {
                var item = Jenkins.get().getItemByFullName(fullName);

                if (!(item instanceof AbstractItem ai)) {
                    // Skip items that aren't backed by config.xml in a standard way
                    continue;
                }

                var entryName = sanitizeZipPath(fullName) + "/config.xml";
                zos.putNextEntry(new ZipEntry(entryName));

                try (InputStream in = ai.getConfigFile().getFile().toPath().toUri().toURL().openStream()) {
                    in.transferTo(zos);
                }

                zos.closeEntry();
            }
        }
    }

    private String sanitizeZipPath(String fullName) {
        // Jenkins fullName uses '/' for folders; we keep it, but block weirdness.
        var s = fullName.replace('\\', '/');

        while (s.startsWith("/")) {
            s = s.substring(1);
        }

        return s;
    }

}
