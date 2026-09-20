package com.jade.integration;

import com.jade.api.CommandOutcome;
import com.jade.api.CommandRequest;
import com.jade.api.CommandResult;
import com.jade.api.CommandStatus;
import com.jade.api.ConfiguredApp;
import com.jade.api.DependencyList;
import com.jade.api.Diagnostic;
import com.jade.api.DiagnosticsReport;
import com.jade.api.ExecutionResult;
import com.jade.api.ExecutionStepResult;
import com.jade.api.FileOpener;
import com.jade.api.FileSearchResult;
import com.jade.api.MainClassCandidates;
import com.jade.api.PlanStep;
import com.jade.api.ProjectContext;
import com.jade.api.ProjectInspectionResult;
import com.jade.api.ProjectInspectionResult.SourceInventory;
import com.jade.api.ProjectOperationResult;
import com.jade.api.ProjectOperationStatus;
import com.jade.api.ProjectTree;
import com.jade.api.TodoFindings;
import com.jade.core.DefaultCommandGateway;
import com.jade.services.app.DesktopAppService;
import com.jade.services.files.ContentSearchService;
import com.jade.services.files.FileSystemFileService;
import com.jade.services.history.SqliteHistoryRepository;
import com.jade.services.project.FileSystemProjectService;
import com.jade.services.project.MavenProjectProcessRunner;
import com.jade.services.search.FileSystemFileSearchService;
import com.jade.services.system.OshiSystemInfoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase E: the self-inspection demo flow validated end-to-end. Every answer
 * derives from real structured state of generated temp fixture projects —
 * no fabricated success. Real Maven runs are skipped when Maven is absent.
 */
class SelfInspectionDemoFlowTest {
    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("UTC");

    @TempDir
    Path temporaryDirectory;

    @Test
    void passingProjectDemoFlow() throws Exception {
        assumeMavenPresent();
        Path project = demoProject(false);
        try (Runtime runtime = new Runtime()) {
            // 1. open project <temp path>
            ProjectContext active = result(runtime.submit("open project " + project), ProjectContext.class);
            assertEquals(project, active.root());

            // 2. what project am I working on
            ProjectContext current = result(
                    runtime.submit("what project am I working on"), ProjectContext.class);
            assertEquals(active.root(), current.root());

            // 3. what kind of project is this
            ProjectInspectionResult inspection = result(
                    runtime.submit("what kind of project is this"), ProjectInspectionResult.class);
            assertEquals("demo", inspection.coordinates().artifactId());
            assertEquals("org.junit.jupiter", inspection.dependencies().getFirst().groupId());

            // 4. give me a project summary
            ProjectInspectionResult summary = result(
                    runtime.submit("give me a project summary"), ProjectInspectionResult.class);
            assertEquals(3, summary.sources().javaSourceFiles());
            assertEquals(1, summary.sources().javaTestFiles());

            // 5. show me the project structure
            ProjectTree tree = result(runtime.submit("show me the project structure"), ProjectTree.class);
            assertTrue(tree.lines().stream().anyMatch(line -> line.contains("pom.xml")));
            assertTrue(tree.lines().stream().anyMatch(line -> line.contains("src/")));

            // 6. what dependencies does it use
            DependencyList dependencies = result(
                    runtime.submit("what dependencies does it use"), DependencyList.class);
            assertEquals(1, dependencies.dependencies().size());
            assertEquals("junit-jupiter", dependencies.dependencies().getFirst().artifactId());

            // 7. what's the main class
            MainClassCandidates mains = result(
                    runtime.submit("what's the main class"), MainClassCandidates.class);
            assertEquals(1, mains.candidates().size());
            assertEquals("com.example.demo.DemoApp", mains.candidates().getFirst().className());

            // 8. are there any todos
            TodoFindings todos = result(runtime.submit("are there any todos"), TodoFindings.class);
            assertEquals(1, todos.findings().size());
            assertEquals("TODO", todos.findings().getFirst().marker());

            // 9. run the tests (real Maven through the production runner)
            ProjectOperationResult run = result(
                    runtime.submit("run the tests"), ProjectOperationResult.class);
            assertEquals(ProjectOperationStatus.SUCCEEDED, run.status(), run.outputSummary());

            // 10. what happened
            com.jade.api.ProjectOutcomeReport happened = result(
                    runtime.submit("what happened"), com.jade.api.ProjectOutcomeReport.class);
            assertEquals(ProjectOperationStatus.SUCCEEDED,
                    happened.operation().orElseThrow().status());
        }
    }

    @Test
    void failingProjectDemoFlow() throws Exception {
        assumeMavenPresent();
        Path project = demoProject(true);
        try (Runtime runtime = new Runtime()) {
            runtime.openProject(project);

            // 11. run the tests → honest BUILD_FAILED
            ProjectOperationResult run = result(
                    runtime.submit("run the tests"), ProjectOperationResult.class);
            assertEquals(ProjectOperationStatus.BUILD_FAILED, run.status());

            // 12. what failed → the failing test, from real Surefire evidence
            DiagnosticsReport report = result(runtime.submit("what failed"), DiagnosticsReport.class);
            assertEquals(1, report.diagnostics().size());
            Diagnostic diagnostic = report.diagnostics().getFirst();
            assertEquals(Diagnostic.Kind.TEST_FAILURE, diagnostic.kind());
            assertEquals("broken", diagnostic.testMethod().orElseThrow());

            // 13. show me the errors → same structured evidence
            DiagnosticsReport errors = result(
                    runtime.submit("show me the errors"), DiagnosticsReport.class);
            assertEquals(1, errors.diagnostics().size());
        }
    }

    @Test
    void compoundPlannerDemoFlow() throws Exception {
        assumeMavenPresent();
        // 14. inspect this project and run the tests
        Path passing = demoProject(false);
        try (Runtime runtime = new Runtime()) {
            runtime.openProject(passing);
            ExecutionResult execution = result(
                    runtime.submit("inspect this project and run the tests"), ExecutionResult.class);
            assertEquals(List.of(PlanStep.INSPECT_PROJECT, PlanStep.RUN_TESTS),
                    execution.trace().stream().map(ExecutionStepResult::step).toList());
            assertTrue(execution.allStepsSucceeded(), execution.toString());
        }

        // 15. run the tests and tell me what failed
        Path failing = demoProject(true);
        try (Runtime runtime = new Runtime()) {
            runtime.openProject(failing);
            ExecutionResult execution = result(
                    runtime.submit("run the tests and tell me what failed"), ExecutionResult.class);
            assertEquals(List.of(PlanStep.RUN_TESTS, PlanStep.DIAGNOSTICS),
                    execution.trace().stream().map(ExecutionStepResult::step).toList());
            assertEquals(ExecutionStepResult.StepStatus.FAILED_RESULT,
                    execution.trace().get(0).status());
            com.jade.api.DiagnosticsReport diagnostics = (com.jade.api.DiagnosticsReport)
                    execution.trace().get(1).result().orElseThrow();
            assertEquals(1, diagnostics.diagnostics().size());
        }

        // 16. build it and tell me what happened
        try (Runtime runtime = new Runtime()) {
            runtime.openProject(passing);
            ExecutionResult execution = result(
                    runtime.submit("build it and tell me what happened"), ExecutionResult.class);
            assertEquals(List.of(PlanStep.RUN_BUILD, PlanStep.LAST_PROJECT_OUTCOME),
                    execution.trace().stream().map(ExecutionStepResult::step).toList());
            ProjectOperationResult build = (ProjectOperationResult)
                    execution.trace().get(0).result().orElseThrow();
            assertEquals(ProjectOperationStatus.SUCCEEDED, build.status());
        }
    }

    @Test
    void fileCapabilitiesStillWorkAlongsideProjectDemo() throws Exception {
        Path project = demoProject(false);
        try (Runtime runtime = new Runtime()) {
            // File context (F3) inside the same session as project context.
            FileSearchResult found = result(runtime.submit("find txt files"), FileSearchResult.class);
            assertEquals(1, found.matches().size());
            runtime.openProject(project);
            result(runtime.submit("give me a project summary"), ProjectInspectionResult.class);
            // The file result set is untouched by project commands.
            assertTrue(runtime.sessionLastResult().isPresent());
            assertEquals(1, runtime.sessionLastResult().orElseThrow().matches().size());
        }
    }

    // ------------------------------------------------------------------

    private void assumeMavenPresent() {
        boolean present = new java.io.File("/opt/homebrew/bin/mvn").exists()
                || new java.io.File("/usr/local/bin/mvn").exists();
        org.junit.jupiter.api.Assumptions.assumeTrue(present, "Maven not available");
    }

    /**
     * Generated demo fixture: pom.xml, three main sources, one test source,
     * one TODO, one dependency; tests fail only when {@code failing} is set.
     */
    private Path demoProject(boolean failing) throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("demo-" + System.nanoTime()));
        Files.writeString(root.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>0.1.0</version>
                    <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.11.4</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.5.2</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """);
        Path main = Files.createDirectories(root.resolve("src/main/java/com/example/demo"));
        Path test = Files.createDirectories(root.resolve("src/test/java/com/example/demo"));
        Files.writeString(main.resolve("DemoApp.java"), """
                package com.example.demo;

                public class DemoApp {
                    public static void main(String[] args) {
                        System.out.println("demo");
                    }
                }
                """);
        Files.writeString(main.resolve("Greeter.java"), "package com.example.demo;\n\nclass Greeter {}\n");
        Files.writeString(main.resolve("Math.java"), "package com.example.demo;\n\nclass Math {}\n");
        Files.writeString(test.resolve("DemoTest.java"), """
                package com.example.demo;

                // TODO replace with real coverage
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                class DemoTest {
                    @Test
                    void broken() {
                        assertEquals(2, %d);
                    }
                }
                """.formatted(failing ? 1 : 2));
        return root;
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
            Files.writeString(scope.resolve("note.txt"), "demo note");
            FileOpener recorder = (path, cancellation) -> { };
            gateway = new DefaultCommandGateway(
                    new DesktopAppService(List.of(
                            new ConfiguredApp("calculator", "Calculator", Set.of("calculator")))),
                    new FileSystemFileSearchService(List.of(scope)),
                    new OshiSystemInfoService(),
                    history,
                    executor,
                    new com.jade.services.files.ScopedFileMutationService(List.of(scope), history),
                    history,
                    List.of(scope),
                    pending -> com.jade.api.ConfirmationHandler.Decision.CONFIRMED,
                    Clock.fixed(NOW, ZONE),
                    new FileSystemFileService(),
                    new ContentSearchService(),
                    recorder,
                    new FileSystemProjectService(),
                    new MavenProjectProcessRunner(600));
        }

        void openProject(Path project) throws Exception {
            result(submit("open project " + project), ProjectContext.class);
        }

        CommandOutcome submit(String text) throws Exception {
            CompletableFuture<CommandOutcome> completion = new CompletableFuture<>();
            gateway.submit(CommandRequest.create(text), ignored -> { }, completion::complete);
            return completion.get(660, TimeUnit.SECONDS);
        }

        java.util.Optional<FileSearchResult> sessionLastResult() {
            return gateway.sessionState().lastSearchResult();
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
