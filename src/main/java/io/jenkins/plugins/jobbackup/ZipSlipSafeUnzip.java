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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Utility class for safely extracting ZIP archives.
 *
 * <h2>Why this exists</h2>
 * ZIP extraction is a common source of security vulnerabilities, most notably
 * <em>Zip Slip</em>, where crafted ZIP entries use relative paths (e.g. {@code ../})
 * or absolute paths to write files outside the intended destination directory.
 *
 * <p>This utility performs strict validation on each ZIP entry to ensure that:</p>
 * <ul>
 *   <li>No absolute paths are allowed.</li>
 *   <li>No parent directory traversal ({@code ..}) is allowed.</li>
 *   <li>The resolved output path always stays within the destination directory.</li>
 * </ul>
 *
 * <h2>Design principles</h2>
 * <ul>
 *   <li>Fail fast: extraction stops immediately on the first unsafe entry.</li>
 *   <li>No partial trust: every entry is validated independently.</li>
 *   <li>No silent recovery: unsafe ZIPs result in an {@link IOException}.</li>
 * </ul>
 *
 * <p>This class is intentionally minimal and self-contained so it can be reused
 * safely across import flows.</p>
 */
public final class ZipSlipSafeUnzip {

    /**
     * Private constructor to prevent instantiation.
     *
     * <p>This class is a pure static utility.</p>
     */
    private ZipSlipSafeUnzip() {
    }

    /**
     * Safely extracts a ZIP file into a destination directory.
     *
     * <h2>Security checks performed</h2>
     * For every {@link ZipEntry} in the archive:
     * <ul>
     *   <li>Rejects entry names that start with {@code /} or {@code \}.</li>
     *   <li>Rejects entry names containing {@code ..} path segments.</li>
     *   <li>Normalizes the resolved output path and verifies it remains under
     *       {@code destDir} using canonical path comparison.</li>
     * </ul>
     *
     * <h2>Filesystem behavior</h2>
     * <ul>
     *   <li>Directories are created as needed.</li>
     *   <li>Files are created or truncated if they already exist.</li>
     *   <li>Existing files outside the destination directory are never touched.</li>
     * </ul>
     *
     * <h2>Error handling</h2>
     * <ul>
     *   <li>If an unsafe entry is detected, extraction stops immediately.</li>
     *   <li>The caller is responsible for cleanup of partially extracted content.</li>
     * </ul>
     *
     * @param zipFile path to the ZIP file to extract.
     * @param destDir destination directory where the ZIP contents should be written.
     * @throws IOException if an I/O error occurs or if the ZIP contains unsafe entries.
     */
    public static void unzip(Path zipFile, Path destDir) throws IOException {
        // Resolve the canonical destination directory once.
        // This is used as the reference root for zip-slip protection.
        var destCanonical = destDir.toRealPath();

        // Open the ZIP as a streaming input to avoid loading it entirely into memory.
        try (var fis = Files.newInputStream(zipFile);
             var zis = new ZipInputStream(fis)) {

            ZipEntry entry;

            // Iterate over each entry in the ZIP archive.
            while ((entry = zis.getNextEntry()) != null) {
                var name = entry.getName();

                // Hard reject clearly unsafe entry names:
                // - absolute paths
                // - Windows absolute-like paths
                // - any parent traversal attempt
                if (name.startsWith("/") || name.startsWith("\\") || name.contains("..")) {
                    throw new IOException("Unsafe ZIP entry: " + name);
                }

                // Resolve the output path and normalize it.
                var outPath = destDir.resolve(name).normalize();

                // Determine the canonical parent directory of the output path.
                // This is what we compare against the canonical destination root.
                var outCanonicalParent = outPath.getParent() != null
                        ? outPath.getParent().toAbsolutePath().normalize()
                        : destDir.toAbsolutePath().normalize();

                // Zip-slip protection:
                // The output path (or its parent) must remain under the destination directory.
                if (!outCanonicalParent.startsWith(destCanonical)) {
                    throw new IOException("ZIP entry escapes destination: " + name);
                }

                if (entry.isDirectory()) {
                    // Directory entry: ensure it exists.
                    Files.createDirectories(outPath);
                } else {
                    // File entry: ensure parent directories exist.
                    assert outPath.getParent() != null;
                    Files.createDirectories(outPath.getParent());

                    // Stream the file contents from the ZIP to disk.
                    // CREATE + TRUNCATE_EXISTING ensures deterministic behavior.
                    try (OutputStream os = Files.newOutputStream(
                            outPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING
                    )) {
                        zis.transferTo(os);
                    }
                }

                // Explicitly close the current ZIP entry before moving to the next.
                zis.closeEntry();
            }
        }
    }

}
