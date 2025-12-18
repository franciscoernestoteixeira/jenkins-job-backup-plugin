package io.jenkins.plugins.jobbackup;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.*;
import jenkins.model.Jenkins;
import jenkins.model.ModifiableTopLevelItemGroup;

import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class ApplyService {

    public Model.ApplyResult apply(List<String> fullNames, Map<String, Path> configByFullName) {
        var r = new Model.ApplyResult();

        for (var fullName : fullNames) {
            var cfg = configByFullName.get(fullName);

            if (cfg == null) {
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

                // IMPORTANT: create via descriptor, not Folder.class
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
        // CloudBees Folder provides the Folder descriptor as a TopLevelItemDescriptor
        var d = Jenkins.get().getDescriptorByType(Folder.DescriptorImpl.class);

        if (d == null) {
            throw new IllegalStateException("CloudBees Folders plugin is required to create folders (descriptor not found).");
        }

        return d;
    }

}
