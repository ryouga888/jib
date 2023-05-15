package com.google.cloud.tools.jib.maven;

import com.google.common.base.Splitter;
import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * DependenciesResolver
 *
 * @author yihua
 * @since 3.3.2
 **/
public class DependenciesResolver {

    private static final ClassLoader classLoader = MavenProjectProperties.class.getClassLoader();

    public List<Path> resolve(MavenProject project) throws IOException {
        final byte[] emptyJarBytes = IOUtils.toByteArray(Objects.requireNonNull(classLoader.getResourceAsStream("empty.jar")));
        Path tmpPath = Paths.get(System.getProperty("java.io.tmpdir"));

        Set<Library> exceptArtifacts = project.getArtifacts().stream()
                //.filter(artifact -> !artifact.equals(project.getArtifact()))
                //.filter(artifact -> artifact.getFile() != null)
                .map(artifact -> getDependencies(artifact.getFile()))
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        List<Path> resolvedArtifacts = new ArrayList<>();
        for (Artifact artifact : project.getArtifacts()) {
            if (contain(artifact, exceptArtifacts)) {
                continue;
            }
            resolvedArtifacts.add(artifact.getFile().toPath());
            List<Library> conflicts = conflict(artifact, exceptArtifacts);
            if (conflicts != null && !conflicts.isEmpty()) {
                for (Library library : conflicts) {
                    String filename = String.format("%s-%s.%s", library.artifactId, library.version, library.type);
                    Path emptyJarPath = Paths.get(tmpPath.toAbsolutePath().toString(), filename);
                    if (!Files.exists(emptyJarPath)) {
                        Files.createFile(emptyJarPath);
                    }

                    Files.write(emptyJarPath, emptyJarBytes);
                    resolvedArtifacts.add(emptyJarPath);
                }
            }
        }

        return resolvedArtifacts;
    }

    private boolean contain(Artifact artifact, Set<Library> exceptArtifacts) {
        return exceptArtifacts.stream().anyMatch(m -> artifact.getGroupId().equals(m.groupId)
                && artifact.getArtifactId().equals(m.artifactId)
                && artifact.getVersion().equals(m.version));
    }

    private List<Library> conflict(Artifact artifact, Set<Library> exceptArtifacts) {
        return exceptArtifacts.stream().filter(m -> artifact.getGroupId().equals(m.groupId)
                        && artifact.getArtifactId().equals(m.artifactId)
                        && !artifact.getVersion().equals(m.version))
                .collect(Collectors.toList());
    }

    private Set<Library> getDependencies(File file) {
        Set<Library> dependencies = new HashSet<>();
        if (file == null) {
            return dependencies;
        }
        try (JarFile jar = new JarFile(file)) {
            JarEntry entry = jar.getJarEntry("META-INF/DEPENDENCIES.MF");
            if (entry == null) {
                return dependencies;
            }
            Set<String> strs;
            try (InputStream is = jar.getInputStream(entry)) {
                strs = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)).lines().collect(Collectors.toSet());
            }
            strs.forEach(s -> {
                List<String> splits = Splitter.on(":").splitToList(s);
                if (splits.size() != 5) {
                    return;
                }
                dependencies.add(new Library(splits.get(0), splits.get(1), splits.get(2), splits.get(3), splits.get(4)));
            });
            return dependencies;
        } catch (IOException e) {
            return dependencies;
        }
    }

    static class Library {
        public String groupId;
        public String artifactId;
        public String version;
        public String scope;
        public String type;


        public Library(String groupId, String artifactId, String version, String scope, String type) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.type = type;
            this.scope = scope;
        }
    }
}
