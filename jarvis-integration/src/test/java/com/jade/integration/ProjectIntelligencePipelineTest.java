package com.jade.integration;

import com.jade.api.CommandOutcome;
import com.jade.api.CommandRequest;
import com.jade.api.CommandResult;
import com.jade.api.CommandStatus;
import com.jade.api.ConfiguredApp;
import com.jade.api.DependencyList;
import com.jade.api.ErrorCode;
import com.jade.api.FileOpener;
import com.jade.api.MainClassCandidates;
import com.jade.api.ProjectInspectionResult;
import com.jade.api.ProjectInspectionResult.SourceInventory;
import com.jade.api.ProjectTree;
import com.jade.api.TodoFindings;
import com.jade.core.DefaultCommandGateway;
import com.jade.services.app.DesktopAppService;
import com.jade.services.files.ContentSearchService;
import com.jade.services.files.FileSystemFileService;
import com.jade.services.history.SqliteHistoryRepository;
import com.jade.services.project.FileSystemProjectService;
import com.jade.services.search.FileSystemFileSearchService;
import com.jade.services.system.OshiSystemInfoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Project intelligence routed through the real parser/gateway/history
 * pipeline. Fixture projects are generated inside {@link TempDir}; the file
 * context and project context are proven independent.
 */
class ProjectIntelligencePipelineTest {
    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("UTC");

    @TempDir
    Path temporaryDirectory;

    @Test
    void inspectionPhraseAnswersFromRealFixtureFacts() throws Exception {
        Path project = fixtureProject(true, false);
        try (Runtime runtime = new Runtime()) {
            runtime.openProject(project);

            ProjectInspectionResult inspection = result(
                    runtime.submit("what kind of project is this"), ProjectInspectionResult.class);
            assertEquals("demo", inspection.coordinates().artifactId());
            assertEquals(2, inspection.sources().javaSourceFiles());
            assertEquals(1, inspection.sources().javaTestFiles());
            assertEquals(1, inspection.mainCandidates().candidates().size());
            assertEquals(1, inspection.todoFindings().size());

            // Other phrases return their typed projections of the same facts.
            ProjectTree tree = result(runtime.submit("show me the project structure"), ProjectTree.class);
            assertTrue(tree.lines().stream().anyMatch(line -> line.contains("pom.xml")));

            SourceInventory counts = result(
                    runtime.submit("how many java files are there"), SourceInventory.class);
            assertEquals(2, counts.javaSourceFiles());

            DependencyList dependencies = result(
                    runtime.submit("what dependencies does it use"), DependencyList.class);
            assertEquals(1, dependencies.dependencies().size());
            assertEquals("junit-jupiter", dependencies.dependencies().getFirst().artifactId());

            MainClassCandidates mains = result(
                    runtime.submit("what is the main class"), MainClassCandidates.class);
            assertEquals("com.example.demo.App", mains.candidates().getFirst().className());

            TodoFindings todos = result(runtime.submit("are there any todos"), TodoFindings.class);
            assertEquals(1, todos.findings().size());
            assertTrue(todos.findings().getFirst().snippet().orElseThrow().contains("wire up"));
        }
    }

    @Test
    void noActiveProjectIsAnHonestRejection() throws Exception {
        try (Runtime runtime = new Runtime()) {
            CommandOutcome outcome = runtime.submit("give me a project summary");
            assertEquals(CommandStatus.REJECTED, outcome.status());
            assertEquals(ErrorCode.INVALID_COMMAND, outcome.error().orElseThrow().code());
            assertTrue(outcome.error().orElseThrow().message().contains("No active project"));
        }
    }

    @Test
    void staleProjectIsClearedAndReported() throws Exception {
        Path project = fixtureProject(false, false);
        try (Runtime runtime = new Runtime()) {
            runtime.openProject(project);
            // Simulate the project vanishing after activation.
            deleteRecursively(project);

            CommandOutcome outcome = runtime.submit("what kind of project is this");
            assertEquals(CommandStatus.REJECTED, outcome.status());
            assertTrue(outcome.error().orElseThrow().message().contains("no longer present"));
        }
    }

    @Test
    void fileContextAndProjectContextRemainIndependent() throws Exception {
        Path scope = Files.createDirectories(temporaryDirectory.resolve("scope"));
        Files.writeString(scope.resolve("notes.txt"), "plain notes", StandardCharsets.UTF_8);
        Path project = fixtureProject(false, false);
        try (Runtime runtime = new Runtime()) {
            runtime.openProject(project);

            // File context still works and is not disturbed by project commands.
            com.jade.api.FileSearchResult found = result(
                    runtime.submit("find txt files"), com.jade.api.FileSearchResult.class);
            assertEquals(1, found.matches().size());

            result(runtime.submit("what kind of project is this"), ProjectInspectionResult.class);

            SourceInventory countsAgain = result(
                    runtime.submit("how many java files are there"), SourceInventory.class);
            assertEquals(2, countsAgain.javaSourceFiles());
            // And the search result set survives the inspection commands.
            assertTrue(runtime.sessionLastResult().isPresent());
            assertEquals(1, runtime.sessionLastResult().orElseThrow().matches().size());
        }
    }

    @Test
    void inspectionCommandsLandInHistory() throws Exception {
        Path project = fixtureProject(false, false);
        try (Runtime runtime = new Runtime()) {
            runtime.openProject(project);
            result(runtime.submit("project summary"), ProjectInspectionResult.class);
            assertTrue(runtime.recentHistoryTexts().stream()
                    .anyMatch(text -> text.contains("project summary")));
        }
    }

    // ------------------------------------------------------------------

    /** Generated fixture project: pom + sources + optional TODO and dep. */
    private Path fixtureProject(boolean withTodo, boolean unusedFlag) throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve(
                "proj-" + System.nanoTime()));
        Files.writeString(root.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>0.1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.11.4</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Path main = Files.createDirectories(root.resolve("src/main/java/com/example/demo"));
        Path test = Files.createDirectories(root.resolve("src/test/java/com/example/demo"));
        String todoLine = withTodo ? "// TODO wire up the module\n" : "";
        Files.writeString(main.resolve("App.java"), """
                package com.example.demo;

                %spublic class App {
                    public static void main(String[] args) {}
                }
                """.formatted(todoLine));
        Files.writeString(main.resolve("Helper.java"), "package com.example.demo;\n\nclass Helper {}\n");
        Files.writeString(test.resolve("AppTest.java"), "package com.example.demo;\n\nclass AppTest {}\n");
        return root;
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private final class Runtime implements AutoCloseable {
        final ExecutorService executor;
        final SqliteHistoryRepository history;
        final DefaultCommandGateway gateway;

        Runtime() throws Exception {
            executor = new ThreadPoolExecutor(
                    2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
            history = new SqliteHistoryRepository(temporaryDirectory.resolve("history.db"));
            Path scope = Files.createDirectories(temporaryDirectory.resolve("scope"));
            FileOpener recorder = (path, cancellation) -> { };
            gateway = new DefaultCommandGateway(
                    new DesktopAppService(List.of(
                            new ConfiguredApp("calculator", "Calculator", Set.of("calculator")))),
                    new FileSystemFileSearchService(List.of(scope)),
                    new OshiSystemInfoService(),
                    history,
                    executor,
                    null,
                    null,
                    List.of(scope),
                    null,
                    Clock.fixed(NOW, ZONE),
                    new FileSystemFileService(),
                    new ContentSearchService(),
                    recorder,
                    new FileSystemProjectService(),
                    new com.jade.services.project.MavenProjectProcessRunner());
        }

        void openProject(Path project) throws Exception {
            result(submit("open project " + project), com.jade.api.ProjectContext.class);
        }

        CommandOutcome submit(String text) throws Exception {
            CompletableFuture<CommandOutcome> completion = new CompletableFuture<>();
            gateway.submit(CommandRequest.create(text), ignored -> { }, completion::complete);
            return completion.get(15, TimeUnit.SECONDS);
        }

        java.util.Optional<com.jade.api.FileSearchResult> sessionLastResult() {
            return gateway.sessionState().lastSearchResult();
        }

        List<String> recentHistoryTexts() throws Exception {
            return history.recent(10, com.jade.api.CancellationToken.NONE).stream()
                    .map(entry -> entry.originalText())
                    .toList();
        }

        @Override
        public void close() throws Exception {
            gateway.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            history.close();
        }
    }

    private static <T extends CommandResult> T result(CommandOutcome outcome, Class<T> type) {
        assertEquals(CommandStatus.SUCCEEDED, outcome.status(), outcome.summary());
        return type.cast(outcome.result().orElseThrow());
    }
}
