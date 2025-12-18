package io.jenkins.plugins.jobbackup;

import hudson.XmlFile;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class ImportSessionService {

    private final Path baseDir = Jenkins.get().getRootDir().toPath()
            .resolve("job-backup").resolve("import");

    public String createSession() {
        return UUID.randomUUID().toString();
    }

    public boolean sessionExists(String sessionId) {
        return Files.isDirectory(sessionDir(sessionId));
    }

    public Path sessionDir(String sessionId) {
        return baseDir.resolve(sessionId);
    }

    public Path unzipDir(String sessionId) {
        return sessionDir(sessionId).resolve("unzipped");
    }

    public record Candidate(String fullName, Path configXmlPath) {
    }

    public List<Candidate> findCandidates(Path unzipDir) throws IOException {
        try (var s = Files.walk(unzipDir)) {
            var configs = s
                    .filter(p -> p.getFileName().toString().equals("config.xml"))
                    .collect(Collectors.toList());

            var result = new ArrayList<Candidate>();

            for (var cfg : configs) {
                var rel = unzipDir.relativize(cfg.getParent()); // folder structure == fullName
                var fullName = rel.toString().replace('\\', '/');

                result.add(new Candidate(fullName, cfg));
            }

            return result;
        }
    }

    public Map<String, Path> indexConfigXml(Path unzipDir) throws IOException {
        var m = new HashMap<String, Path>();

        for (var c : findCandidates(unzipDir)) {
            m.put(c.fullName(), c.configXmlPath());
        }

        return m;
    }

    private XmlFile resultFile(String sessionId) throws IOException {
        Files.createDirectories(sessionDir(sessionId));
        var file = sessionDir(sessionId).resolve("result.xml");

        return new XmlFile(file.toFile());
    }

    public void writeResult(String sessionId, Model.ApplyResult result) throws IOException {
        resultFile(sessionId).write(result);
    }

    public Optional<Model.ApplyResult> readResult(String sessionId) {
        try {
            var file = sessionDir(sessionId).resolve("result.xml");

            if (!Files.exists(file)) {
                return Optional.empty();
            }

            var o = new XmlFile(file.toFile()).read();

            return Optional.of((Model.ApplyResult) o);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

}
