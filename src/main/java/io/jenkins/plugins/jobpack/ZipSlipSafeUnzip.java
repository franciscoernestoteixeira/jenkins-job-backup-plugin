package io.jenkins.plugins.jobpack;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ZipSlipSafeUnzip {

    private ZipSlipSafeUnzip() {
    }

    public static void unzip(Path zipFile, Path destDir) throws IOException {
        var destCanonical = destDir.toRealPath();

        try (var fis = Files.newInputStream(zipFile);
             var zis = new ZipInputStream(fis)) {

            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                var name = entry.getName();

                // Hard reject absolute or suspicious paths
                if (name.startsWith("/") || name.startsWith("\\") || name.contains("..")) {
                    throw new IOException("Unsafe ZIP entry: " + name);
                }

                var outPath = destDir.resolve(name).normalize();
                var outCanonicalParent = outPath.getParent() != null
                        ? outPath.getParent().toAbsolutePath().normalize()
                        : destDir.toAbsolutePath().normalize();

                // Zip-slip check: output must stay under destDir
                if (!outCanonicalParent.startsWith(destCanonical)) {
                    throw new IOException("ZIP entry escapes destination: " + name);
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    assert outPath.getParent() != null;

                    Files.createDirectories(outPath.getParent());

                    try (OutputStream os = Files.newOutputStream(outPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        zis.transferTo(os);
                    }
                }

                zis.closeEntry();
            }
        }
    }

}
