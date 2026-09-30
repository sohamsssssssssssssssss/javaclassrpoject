package com.jade.services.project;

import com.jade.api.BuildSystem;
import com.jade.api.DependencyInfo;
import com.jade.api.ErrorCode;
import com.jade.api.MainClassCandidates;
import com.jade.api.ProjectContext;
import com.jade.api.ProjectInspectionResult;
import com.jade.api.ProjectTree;
import com.jade.api.ServiceException;
import com.jade.api.TodoFinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static Maven project inspection over generated temporary fixtures only.
 * Proves POM parsing (normal, parent-inherited, property-based, malformed),
 * source/test/resource counting, exclusion of build outputs, package
 * counting, main-class detection (0/1/many), TODO/FIXME bounds and
 * tree/traversal bounds. The real filesystem is never touched.
 */
class MavenProjectInspectionServiceTest {

    @TempDir
    Path temporaryDirectory;

    private final MavenProjectInspectionService service = new MavenProjectInspectionService();

    // ------------------------------------------------------------------
    // Fixture helpers (generated, temp-scoped).
    // ------------------------------------------------------------------

    private Path project(String pom) throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("demo-" + System.nanoTime()));
        Files.writeString(root.resolve("pom.xml"), pom);
        return root;
    }

    private static final String BASIC_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>demo</artifactId>
                <version>1.2.3</version>
                <packaging>jar</packaging>
                <name>Demo Project</name>
                <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <demo.version>9.9.9</demo.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <version>5.11.4</version>
                        <scope>test</scope>
                    </dependency>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>library</artifactId>
                        <version>${demo.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>inherited</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """;

    private Path standardProject() throws Exception {
        Path root = project(BASIC_POM);
        Path mainJava = Files.createDirectories(root.resolve("src/main/java/com/example/demo"));
        Path testJava = Files.createDirectories(root.resolve("src/test/java/com/example/demo"));
        Path resources = Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(mainJava.resolve("App.java"), """
                package com.example.demo;

                public class App {
                    public static void main(String[] args) {
                        System.out.println("hi");
                    }
                }
                """);
        Files.writeString(mainJava.resolve("Util.java"), """
                package com.example.demo;

                // TODO: tighten bounds
                public class Util {
                }
                """);
        Files.writeString(testJava.resolve("AppTest.java"), """
                package com.example.demo;

                // FIXME: flaky on windows
                class AppTest {
                }
                """);
        Files.writeString(resources.resolve("app.properties"), "key=value");
        // Build output that must be excluded from every count.
        Path target = Files.createDirectories(root.resolve("target/classes/com/example/demo"));
        Files.writeString(target.resolve("Impostor.java"), "package com.example.demo;\n// TODO: fake\n");
        return root;
    }

    private ProjectContext context(Path root) {
        return new ProjectContext(root, root.getFileName().toString(),
                BuildSystem.MAVEN, root.resolve("pom.xml"));
    }

    // ------------------------------------------------------------------
    // POM metadata and dependencies.
    // ------------------------------------------------------------------

    @Test
    void readsCoordinatesAndResolvesInPomPropertyVersions() throws Exception {
        ProjectInspectionResult result = service.inspect(context(project(BASIC_POM)));
        ProjectInspectionResult.Coordinates c = result.coordinates();
        assertEquals("com.example", c.groupId());
        assertEquals("demo", c.artifactId());
        assertEquals("1.2.3", c.version());
        assertEquals("jar", c.packaging());
        assertEquals("Demo Project", c.name().orElseThrow());
        assertEquals("21", c.javaVersion().orElseThrow());
    }

    @Test
    void dependenciesAreTypedWithExplicitVersionAndScope() throws Exception {
        ProjectInspectionResult result = service.inspect(context(project(BASIC_POM)));
        assertEquals(3, result.dependencies().size());

        DependencyInfo junit = result.dependencies().getFirst();
        assertEquals("org.junit.jupiter", junit.groupId());
        assertEquals("junit-jupiter", junit.artifactId());
        assertEquals("5.11.4", junit.version().orElseThrow());
        assertEquals("test", junit.scope().orElseThrow());

        // Property version resolved from the same POM.
        DependencyInfo library = result.dependencies().get(1);
        assertEquals("9.9.9", library.version().orElseThrow());
        assertTrue(library.scope().isEmpty());

        // Parent-inherited version is honestly UNKNOWN; the explicit
        // groupId/artifactId are carried verbatim.
        DependencyInfo inherited = result.dependencies().get(2);
        assertTrue(inherited.version().isEmpty());
        assertEquals("com.example", inherited.groupId());
        assertEquals("inherited", inherited.artifactId());
    }

    @Test
    void parentInheritedCoordinatesAreReportedAsUnknown() throws Exception {
        String childPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                </project>
                """;
        ProjectInspectionResult result = service.inspect(context(project(childPom)));
        assertEquals("child", result.coordinates().artifactId());
        assertEquals(DependencyInfo.UNKNOWN, result.coordinates().groupId());
        assertEquals(DependencyInfo.UNKNOWN, result.coordinates().version());
        assertTrue(result.coordinates().name().isEmpty());
        assertTrue(result.coordinates().javaVersion().isEmpty());
        assertTrue(result.dependencies().isEmpty(), "no declared dependencies in the child POM");
    }

    @Test
    void malformedPomIsAStructuredFailureNotACrash() throws Exception {
        Path root = project("<project><unclosed></project");
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.inspect(context(root)));
        assertEquals(ErrorCode.IO_FAILURE, exception.error().code());
        assertTrue(exception.error().message().contains("pom.xml"));
    }

    // ------------------------------------------------------------------
    // Source inventory: counts, exclusions, packages, roots.
    // ------------------------------------------------------------------

    @Test
    void countsSourcesTestsResourcesAndPackagesAndExcludesTarget() throws Exception {
        ProjectInspectionResult result = service.inspect(context(standardProject()));
        ProjectInspectionResult.SourceInventory sources = result.sources();
        assertEquals(2, sources.javaSourceFiles(), "target/ impostors must not be counted");
        assertEquals(1, sources.javaTestFiles());
        // app.properties plus pom.xml itself (a non-Java file).
        assertEquals(2, sources.resourceFiles());
        assertEquals(1, sources.packages());
        assertEquals(List.of("src/main/java"), sources.sourceRoots());
        assertEquals(List.of("src/test/java"), sources.testRoots());
    }

    @Test
    void emptyProjectReportsZerosWithoutInventingFacts() throws Exception {
        ProjectInspectionResult result = service.inspect(context(project(BASIC_POM)));
        assertEquals(0, result.sources().javaSourceFiles());
        assertEquals(0, result.sources().javaTestFiles());
        // pom.xml itself is the one non-Java file present.
        assertEquals(1, result.sources().resourceFiles());
        assertEquals(0, result.sources().packages());
    }

    // ------------------------------------------------------------------
    // Main-class detection: 0 / 1 / many, real signatures only.
    // ------------------------------------------------------------------

    @Test
    void detectsExactlyOneMainCandidate() throws Exception {
        ProjectInspectionResult result = service.inspect(context(standardProject()));
        assertEquals(1, result.mainCandidates().candidates().size());
        MainClassCandidates.Candidate candidate = result.mainCandidates().candidates().getFirst();
        assertEquals("com.example.demo.App", candidate.className());
        assertTrue(candidate.signature().contains("main"));
    }

    @Test
    void reportsMultipleMainCandidatesWithoutGuessing() throws Exception {
        Path root = project(BASIC_POM);
        Path mainJava = Files.createDirectories(root.resolve("src/main/java/com/example"));
        Files.writeString(mainJava.resolve("First.java"), """
                package com.example;
                public class First {
                    public static void main(String[] args) {}
                }
                """);
        Files.writeString(mainJava.resolve("Second.java"), """
                package com.example;
                public class Second {
                    public static void main(String[] args) {}
                }
                """);
        ProjectInspectionResult result = service.inspect(context(root));
        assertEquals(2, result.mainCandidates().candidates().size());
    }

    @Test
    void aClassWithoutMainYieldsNoCandidates() throws Exception {
        Path root = project(BASIC_POM);
        Path mainJava = Files.createDirectories(root.resolve("src/main/java/com/example"));
        Files.writeString(mainJava.resolve("Plain.java"), """
                package com.example;
                public class Plain {
                    public void run() {}
                }
                """);
        ProjectInspectionResult result = service.inspect(context(root));
        assertTrue(result.mainCandidates().candidates().isEmpty());
    }

    // ------------------------------------------------------------------
    // TODO/FIXME: locations, bounded snippets, no binary scanning.
    // ------------------------------------------------------------------

    @Test
    void reportsTodoAndFixmeLocationsWithBoundedSnippets() throws Exception {
        ProjectInspectionResult result = service.inspect(context(standardProject()));
        assertEquals(2, result.todoFindings().size());
        TodoFinding todo = result.todoFindings().getFirst();
        assertEquals("TODO", todo.marker());
        assertEquals("src/main/java/com/example/demo/Util.java", todo.path());
        assertEquals(3, todo.lineNumber());
        assertTrue(todo.snippet().orElseThrow().contains("tighten bounds"));
        assertEquals("FIXME", result.todoFindings().getLast().marker());
    }

    @Test
    void todoFindingsAreBoundedAndTruncationIsTruthful() throws Exception {
        Path root = project(BASIC_POM);
        Path mainJava = Files.createDirectories(root.resolve("src/main/java/com/example"));
        StringBuilder manyTodos = new StringBuilder("package com.example;\npublic class Noisy {\n");
        for (int index = 0; index < 60; index++) {
            manyTodos.append("    // TODO item ").append(index).append("\n");
        }
        manyTodos.append("}\n");
        Files.writeString(mainJava.resolve("Noisy.java"), manyTodos.toString());
        ProjectInspectionResult result = service.inspect(context(root));
        assertEquals(50, result.todoFindings().size());
        assertTrue(result.todosTruncated());
    }

    @Test
    void longTodoLinesAreSnippedNotDumped() throws Exception {
        Path root = project(BASIC_POM);
        Path mainJava = Files.createDirectories(root.resolve("src/main/java/com/example"));
        Files.writeString(mainJava.resolve("Long.java"),
                "package com.example;\n// TODO " + "x".repeat(400) + "\n");
        ProjectInspectionResult result = service.inspect(context(root));
        TodoFinding finding = result.todoFindings().getFirst();
        assertTrue(finding.snippet().orElseThrow().length() <= MavenProjectInspectionService.SNIPPET_LIMIT + 1,
                "snippet must respect the bound (plus ellipsis)");
    }

    @Test
    void binaryFilesAreNotScannedForTodos() throws Exception {
        Path root = project(BASIC_POM);
        Path mainJava = Files.createDirectories(root.resolve("src/main/java/com/example"));
        Files.writeString(mainJava.resolve("Marker.java"), "package com.example;\n// TODO real\n");
        Files.write(root.resolve("blob.bin"), new byte[]{0x00, (byte) 0xFF, 0x00, 0x02});
        ProjectInspectionResult result = service.inspect(context(root));
        assertEquals(1, result.todoFindings().size(), "only the .java file may be scanned");
    }

    // ------------------------------------------------------------------
    // Tree and traversal bounds; symlink safety.
    // ------------------------------------------------------------------

    @Test
    void treeIsBoundedAndTruncationIsTruthful() throws Exception {
        Path root = project(BASIC_POM);
        // Deeper than MAX_DEPTH and more files than MAX_LINES.
        Path deep = root;
        for (int level = 0; level < 10; level++) {
            deep = Files.createDirectories(deep.resolve("level" + level));
        }
        Path flat = Files.createDirectories(root.resolve("flat"));
        for (int index = 0; index < 500; index++) {
            Files.writeString(flat.resolve("file" + index + ".txt"), "x");
        }
        ProjectInspectionResult result = service.inspect(context(root));
        ProjectTree tree = result.tree();
        assertTrue(tree.lines().size() <= ProjectTree.MAX_LINES);
        assertTrue(tree.truncated(), "both bounds were exceeded; tree must say so");
        assertTrue(result.scanIncomplete(), "traversal bound must be reported truthfully");
    }

    @Test
    void symlinksAreNotFollowed() throws Exception {
        Path root = project(BASIC_POM);
        Path mainJava = Files.createDirectories(root.resolve("src/main/java/com/example"));
        Files.writeString(mainJava.resolve("Marker.java"), "package com.example;\n// TODO real\n");
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Files.writeString(outside.resolve("Outside.java"), "package outside;\n// TODO outside\n");
        try {
            Files.createSymbolicLink(root.resolve("src/main/java/com/example/link"),
                    outside);
        } catch (UnsupportedOperationException | IOException e) {
            // Filesystem without symlink support: the walk's no-follow
            // behaviour is proven on every normal developer filesystem.
            return;
        }
        ProjectInspectionResult result = service.inspect(context(root));
        assertEquals(1, result.todoFindings().size(),
                "the symlinked outside directory must not be traversed");
        assertTrue(result.todoFindings().getFirst().path().contains("Marker.java"));
    }

    @Test
    void nonStandardJavaFilesStillCountAsSources() throws Exception {
        Path root = project(BASIC_POM);
        Files.writeString(root.resolve("Loose.java"), "class Loose { }\n");
        ProjectInspectionResult result = service.inspect(context(root));
        assertEquals(1, result.sources().javaSourceFiles());
    }

    // ------------------------------------------------------------------
    // Stale/missing project: honest structured rejections.
    // ------------------------------------------------------------------

    @Test
    void missingProjectRootIsAStructuredRejection() {
        ProjectContext stale = new ProjectContext(
                Paths.get(temporaryDirectory.toString(), "does-not-exist"),
                "gone", BuildSystem.MAVEN,
                Paths.get(temporaryDirectory.toString(), "does-not-exist", "pom.xml"));
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.inspect(stale));
        assertEquals(ErrorCode.INVALID_COMMAND, exception.error().code());
        assertTrue(exception.error().message().contains("no longer present"));
    }

    @Test
    void missingDescriptorIsAStructuredRejection() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("bare-" + System.nanoTime()));
        ProjectContext bare = new ProjectContext(root, "bare", BuildSystem.MAVEN,
                root.resolve("pom.xml"));
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.inspect(bare));
        assertTrue(exception.error().message().contains("descriptor"));
    }

    // ------------------------------------------------------------------
    // Ordering determinism.
    // ------------------------------------------------------------------

    @Test
    void inspectionIsDeterministicAcrossRepeatedRuns() throws Exception {
        Path root = standardProject();
        ProjectInspectionResult first = service.inspect(context(root));
        ProjectInspectionResult second = service.inspect(context(root));
        assertEquals(first.tree().lines(), second.tree().lines());
        assertEquals(first.todoFindings(), second.todoFindings());
        assertEquals(first.mainCandidates(), second.mainCandidates());
        assertEquals(first.dependencies(), second.dependencies());
    }
}
