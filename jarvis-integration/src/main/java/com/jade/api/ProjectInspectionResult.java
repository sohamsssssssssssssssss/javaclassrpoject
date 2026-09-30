package com.jade.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed result of one bounded project inspection, produced by
 * {@link ProjectInspectionService} from the validated active
 * {@link ProjectContext} without modifying the project. Everything is static
 * fact collection — no build execution, no resolution, no invented values.
 */
public record ProjectInspectionResult(
        Coordinates coordinates,
        SourceInventory sources,
        ProjectTree tree,
        List<DependencyInfo> dependencies,
        MainClassCandidates mainCandidates,
        List<TodoFinding> todoFindings,
        boolean todosTruncated,
        boolean scanIncomplete) implements CommandResult {
    public ProjectInspectionResult {
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(tree, "tree");
        dependencies = List.copyOf(dependencies);
        Objects.requireNonNull(mainCandidates, "mainCandidates");
        todoFindings = List.copyOf(todoFindings);
    }

    /** Maven coordinates as declared in {@code pom.xml}; UNKNOWN when inherited or absent. */
    public record Coordinates(
            String groupId,
            String artifactId,
            String version,
            String packaging,
            Optional<String> name,
            Optional<String> javaVersion) {
        public Coordinates {
            groupId = Objects.requireNonNull(groupId, "groupId");
            artifactId = Objects.requireNonNull(artifactId, "artifactId");
            version = Objects.requireNonNull(version, "version");
            packaging = Objects.requireNonNull(packaging, "packaging");
            name = Objects.requireNonNull(name, "name");
            javaVersion = Objects.requireNonNull(javaVersion, "javaVersion");
        }
    }

    /** Bounded source inventory counts; never a file dump. Renderable on its own. */
    public record SourceInventory(
            int javaSourceFiles,
            int javaTestFiles,
            int resourceFiles,
            int packages,
            List<String> sourceRoots,
            List<String> testRoots) implements CommandResult {
        public SourceInventory {
            sourceRoots = List.copyOf(sourceRoots);
            testRoots = List.copyOf(testRoots);
        }
    }
}
