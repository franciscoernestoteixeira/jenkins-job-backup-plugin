/*
 * MIT License
 *
 * Copyright (c) 2025
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * of the Software without restriction, including without limitation the rights
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

import hudson.XmlFile;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages "import sessions" for the Job Backup &amp; Restore plugin.
 *
 * <h2>What an import session is</h2>
 * An import flow typically spans multiple HTTP requests:
 * <ul>
 *   <li>User uploads a ZIP</li>
 *   <li>Server unzips and discovers candidates</li>
 *   <li>User reviews/selects which items to apply</li>
 *   <li>Server applies the selection and shows results</li>
 * </ul>
 *
 * <p>This class provides a simple filesystem-backed session mechanism that stores per-session
 * extracted files and a serialized apply result.</p>
 *
 * <h2>Storage layout</h2>
 * Sessions are stored under Jenkins home:
 * <pre>{@code
 * $JENKINS_HOME/job-backup/import/<sessionId>/
 *   unzipped/               (extracted ZIP content)
 *   result.xml              (serialized Model.ApplyResult)
 * }</pre>
 *
 * <h2>Concurrency and safety</h2>
 * This implementation is intentionally lightweight:
 * <ul>
 *   <li>Sessions are represented by directories; existence checks use {@link Files#isDirectory(Path)}.</li>
 *   <li>Deletion is best-effort and ignores per-file errors.</li>
 *   <li>No locking is performed; the controller layer should avoid concurrent writes to the same session.</li>
 * </ul>
 *
 * <h2>Security considerations</h2>
 * Session IDs are UUID strings and are used to locate directories under a fixed base directory.
 * The service assumes:
 * <ul>
 *   <li>Session IDs are generated server-side via {@link #createSession()}.</li>
 *   <li>The caller validates/controls sessionId input to avoid path traversal risk.</li>
 * </ul>
 * This class does not perform explicit validation of session IDs; it relies on the fixed {@code baseDir}
 * and server-side generation to keep sessions contained.
 */
public class ImportSessionService {

    /**
     * Base directory for all import sessions.
     *
     * <p>Resolved under {@code $JENKINS_HOME} to ensure persistence across restarts and correct
     * access permissions for the Jenkins process.</p>
     */
    private final Path baseDir = Jenkins.get().getRootDir().toPath()
            .resolve("job-backup").resolve("import");

    /**
     * Creates a new session identifier.
     *
     * <p>The returned value is intended to be used as a directory name under {@link #baseDir}.</p>
     *
     * @return a random UUID string suitable for use as a session id.
     */
    public String createSession() {
        return UUID.randomUUID().toString();
    }

    /**
     * Determines whether a session directory exists.
     *
     * @param sessionId session identifier.
     * @return true if {@code $baseDir/<sessionId>} exists and is a directory.
     */
    public boolean sessionExists(String sessionId) {
        return Files.isDirectory(sessionDir(sessionId));
    }

    /**
     * Deletes a session directory and all of its contents.
     *
     * <p>This is implemented as a recursive walk where deeper paths are deleted first
     * (reverse order), so directories are removed after their contents.</p>
     *
     * <p>Deletion is best-effort: individual file deletion errors are ignored to avoid blocking
     * cleanup during partial failures (e.g., transient FS errors, file locks on Windows).</p>
     *
     * @param sessionId session identifier.
     * @throws IOException if the directory walk cannot be created or traversed.
     */
    public void deleteSession(String sessionId) throws IOException {
        var dir = sessionDir(sessionId);
        if (Files.notExists(dir)) {
            return;
        }

        // Walk the directory tree and delete children before parents.
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // Best-effort cleanup: ignore per-path delete failures.
                            // Callers may choose to log failures at a higher level if desired.
                        }
                    });
        }
    }

    /**
     * Returns the filesystem path for a given session.
     *
     * @param sessionId session identifier.
     * @return {@code $baseDir/<sessionId>}.
     */
    public Path sessionDir(String sessionId) {
        return baseDir.resolve(sessionId);
    }

    /**
     * Returns the directory where a session's ZIP is extracted.
     *
     * @param sessionId session identifier.
     * @return {@code $baseDir/<sessionId>/unzipped}.
     */
    public Path unzipDir(String sessionId) {
        return sessionDir(sessionId).resolve("unzipped");
    }

    /**
     * Represents a restore candidate discovered in an extracted ZIP.
     *
     * <p>The ZIP layout produced by {@link ExportService} places each {@code config.xml} under
     * a directory named by the Jenkins {@code fullName}:</p>
     * <pre>{@code
     * <fullName>/config.xml
     * }</pre>
     *
     * @param fullName Jenkins full name represented by the directory path.
     * @param configXmlPath filesystem path to the discovered {@code config.xml}.
     */
    public record Candidate(String fullName, Path configXmlPath) {
    }

    /**
     * Walks an extracted ZIP directory and discovers restore candidates.
     *
     * <h2>Discovery rule</h2>
     * Any file named {@code config.xml} is considered a candidate. The candidate full name is derived from
     * the relative directory path containing the config:
     * <ul>
     *   <li>{@code unzipDir.relativize(cfg.getParent())} yields a path like {@code "A/B/job"}.</li>
     *   <li>The path is normalized to use {@code '/'} separators, matching Jenkins {@code fullName} format.</li>
     * </ul>
     *
     * <p>This method does not validate the XML or confirm that the referenced item exists in Jenkins.
     * It only builds a list of potential apply targets.</p>
     *
     * @param unzipDir directory containing extracted ZIP content.
     * @return list of discovered candidates (possibly empty).
     * @throws IOException if directory walking fails.
     */
    public List<Candidate> findCandidates(Path unzipDir) throws IOException {
        // Walk the extracted directory tree and collect any "config.xml" files.
        try (var s = Files.walk(unzipDir)) {
            var configs = s
                    .filter(p -> p.getFileName().toString().equals("config.xml"))
                    .collect(Collectors.toList());

            var result = new ArrayList<Candidate>();

            for (var cfg : configs) {
                // The folder structure in the ZIP encodes the Jenkins fullName.
                // cfg.getParent() points to "<fullName>/" directory.
                var rel = unzipDir.relativize(cfg.getParent());
                var fullName = rel.toString().replace('\\', '/');

                result.add(new Candidate(fullName, cfg));
            }

            return result;
        }
    }

    /**
     * Creates a map of {@code fullName -> config.xml path} for all candidates under an unzip directory.
     *
     * <p>If multiple candidates resolve to the same full name, the later one wins (last write to the map).</p>
     * In normal operation, ZIPs created by {@link ExportService} should not contain duplicates.
     *
     * @param unzipDir extracted ZIP directory.
     * @return map keyed by Jenkins full name.
     * @throws IOException if candidate discovery fails.
     */
    public Map<String, Path> indexConfigXml(Path unzipDir) throws IOException {
        var m = new HashMap<String, Path>();

        for (var c : findCandidates(unzipDir)) {
            m.put(c.fullName(), c.configXmlPath());
        }

        return m;
    }

    /**
     * Resolves the {@link XmlFile} used to persist the apply result for a session.
     *
     * <p>This method ensures the session directory exists before returning the XmlFile handle.</p>
     *
     * @param sessionId session identifier.
     * @return an {@link XmlFile} pointing to {@code $baseDir/<sessionId>/result.xml}.
     * @throws IOException if the session directory cannot be created.
     */
    private XmlFile resultFile(String sessionId) throws IOException {
        Files.createDirectories(sessionDir(sessionId));
        var file = sessionDir(sessionId).resolve("result.xml");

        return new XmlFile(file.toFile());
    }

    /**
     * Writes an {@link Model.ApplyResult} into the session's {@code result.xml}.
     *
     * <p>{@link XmlFile#write(Object)} uses XStream serialization under the hood. For Jenkins plugins,
     * this is a common way to persist small structured objects on disk.</p>
     *
     * @param sessionId session identifier.
     * @param result apply result to serialize.
     * @throws IOException if the file cannot be created or written.
     */
    public void writeResult(String sessionId, Model.ApplyResult result) throws IOException {
        resultFile(sessionId).write(result);
    }

    /**
     * Reads a previously stored {@link Model.ApplyResult} for the given session.
     *
     * <p>This method is intentionally defensive:</p>
     * <ul>
     *   <li>If {@code result.xml} does not exist, returns {@link Optional#empty()}.</li>
     *   <li>If deserialization fails for any reason, returns {@link Optional#empty()} rather than
     *       failing the caller flow.</li>
     * </ul>
     *
     * @param sessionId session identifier.
     * @return the stored {@link Model.ApplyResult}, if present and readable.
     */
    public Optional<Model.ApplyResult> readResult(String sessionId) {
        try {
            var file = sessionDir(sessionId).resolve("result.xml");

            if (!Files.exists(file)) {
                return Optional.empty();
            }

            // XmlFile.read() returns Object; we expect Model.ApplyResult as written by writeResult().
            var o = new XmlFile(file.toFile()).read();

            return Optional.of((Model.ApplyResult) o);
        } catch (Exception e) {
            // Defensive: treat any I/O, parsing, or class-cast failure as "no usable result".
            return Optional.empty();
        }
    }

}
