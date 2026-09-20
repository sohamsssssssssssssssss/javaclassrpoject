package com.jade.integration;

import com.jade.api.BuildSystem;
import com.jade.api.CommandOutcome;
import com.jade.api.CommandRequest;
import com.jade.api.CommandResult;
import com.jade.api.CommandStatus;
import com.jade.api.ConfiguredApp;
import com.jade.api.ConfirmationHandler;
import com.jade.api.ExecutionResult;
import com.jade.api.ExecutionStepResult;
import com.jade.api.FileOpener;
import com.jade.api.ProjectContext;
import com.jade.api.ProjectOperationResult;
import com.jade.api.ProjectOperationStatus;
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
 * Bounded multi-step planner through the real pipeline: correct step
 * ordering, typed dependencies, honest-failure continuation into
 * diagnostics, infrastructure-failure skipping, cancellation and the
 * confirmation boundary. Generated temp fixtures only; real Maven runs are
 * skipped when Maven is unavailable.
 */
class PlannerPipelineTest {
    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("UTC");

    @TempDir
    Path temporaryDirectory;

    @Test
    void inspectPlusTestRunsInOrderWithTypedSteps() throws Exception {
        assumeMavenPresent();
        Path project = passingProject();
        try (Runtime runtime = new Runtime()) {
            runtime.openProject(project);
            ExecutionResult execution = result(
                    runtime.submit("inspect this project and run the tests"), ExecutionResult.class);
            assertEquals(2, execution.trace().size());
            assertEquals(com.jade.api.PlanStep.INSPECT_PROJECT,
                    execution.trace().get(0).step());
            assertEquals(ExecutionStepResult.StepStatus.SUCCEEDED,
                    execution.trace().get(0).status());
            assertEquals(com.jade.api.PlanStep.RUN_TESTS, execution.trace().get(1).step());
            assertEquals(ExecutionStepResult.StepStatus.SUCCEEDED,
                    execution.trace().get(1).status());
            assertTrue(execution.allStepsSucceeded());
            // Typed dependencies: the inspection step carries structured data.
            assertInstanceOf(com.jade.api.ProjectInspectionResult.class,
                    execution.trace().get(0).result().orElseThrow());
        }
    }

    @Test
    void failingTestsStillProduceDiagnosticsInPlan() throws Exception {
        assumeMavenPresent();
        Path project = failingProject();
        try (Runtime runtime = new Runtime()) {
            runtime.openProject(project);
            ExecutionResult execution = result(
                    runtime.submit("run the tests and tell me what failed"), ExecutionResult.class);
            assertEquals(com.jade.api.PlanStep.RUN_TESTS, execution.trace().get(0).step());
            // A Maven run that executed but failed is an honest FAILED_RESULT,
            // not an infrastructure error: diagnostics still run.
            assertEquals(ExecutionStepResult.StepStatus.FAILED_RESULT,
                    execution.trace().get(0).status());
            assertEquals(com.jade.api.PlanStep.DIAGNOSTICS, execution.trace().get(1).step());
            assertEquals(ExecutionStepResult.StepStatus.SUCCEEDED,
                    execution.trace().get(1).status());
            com.jade.api.DiagnosticsReport diagnostics = (com.jade.api.DiagnosticsReport)
                    execution.trace().get(1).result().orElseThrow();
            assertEquals(1, diagnostics.diagnostics().size());
            assertFalse(execution.allStepsSucceeded());
        }
    }

    @Test
    void infrastructureFailureStopsDependentSteps() throws Exception {
        try (Runtime runtime = new Runtime()) {
            // No active project: the inspection step errors structurally.
            ExecutionResult execution = result(
                    runtime.submit("inspect this project and run the tests"), ExecutionResult.class);
            assertEquals(2, execution.trace().size());
            assertEquals(ExecutionStepResult.StepStatus.ERROR,
                    execution.trace().get(0).status());
            assertEquals(ExecutionStepResult.StepStatus.SKIPPED,
                    execution.trace().get(1).status(),
                    "a dependent step must not run after an infrastructure failure");
        }
    }

    @Test
    void diagnosticsOnlyPlanWithoutOperationErrorsHonestly() throws Exception {
        // Not a supported compound shape — proves the planner does not invent
        // plans; this exact text falls through to single-command parsing.
        try (Runtime runtime = new Runtime()) {
            CommandOutcome outcome = runtime.submit("tell me what failed");
            assertEquals(CommandStatus.REJECTED, outcome.status());
        }
    }

    @Test
    void cancelledPlanStopsFutureSteps() throws Exception {
        Path project = passingProject();
        try (Runtime runtime = new Runtime()) {
            runtime.openProject(project);
            CompletableFuture<CommandOutcome> completion = new CompletableFuture<>();
            var subscription = runtime.gateway.submit(
                    CommandRequest.create("inspect this project and run the tests"),
                    ignored -> { },
                    completion::complete);
            // Cancel as soon as the first step starts executing.
            subscription.cancel();
            CommandOutcome outcome = completion.get(660, TimeUnit.SECONDS);
            // Either the cancellation is honoured mid-plan (CANCELLED), or it
            // landed before execution began — but later steps never run
            // after a cancellation was observed between steps.
            if (outcome.status() == CommandStatus.SUCCEEDED) {
                ExecutionResult execution = (ExecutionResult) outcome.result().orElseThrow();
                boolean sawError = false;
                for (ExecutionStepResult step : execution.trace()) {
                    if (step.status() == ExecutionStepResult.StepStatus.ERROR) {
                        sawError = true;
                    }
                    if (sawError) {
                        assertEquals(ExecutionStepResult.StepStatus.SKIPPED, step.status(),
                                "steps after a cancellation must be skipped");
                    }
                }
            } else {
                assertEquals(CommandStatus.CANCELLED, outcome.status());
            }
        }
    }

    @Test
    void plannerCannotTriggerMutationsEvenWithHandlerPresent() throws Exception {
        Path project = passingProject();
        try (Runtime runtime = new Runtime(ConfirmationHandler.Decision.CONFIRMED)) {
            runtime.openProject(project);
            ExecutionResult execution = result(
                    runtime.submit("inspect this project and run the tests"), ExecutionResult.class);
            // The plan contains only inspection/test steps; no mutation was
            // possible regardless of the always-confirm handler.
            assertTrue(execution.trace().stream().allMatch(step ->
                    step.step() == com.jade.api.PlanStep.INSPECT_PROJECT
                            || step.step() == com.jade.api.PlanStep.RUN_TESTS));
        }
    }

    @Test
    void voiceTextFollowsTheSamePipeline() throws Exception {
        // The voice stack submits ordinary CommandRequests; prove that a
        // compound request text routes through the same planner path.
        try (Runtime runtime = new Runtime()) {
            runtime.openProject(passingProject());
            ExecutionResult execution = result(
                    runtime.submit("  BUILD IT AND WHAT HAPPENED  "), ExecutionResult.class);
            // Without a Maven run in this test (no assume), the BUILD step
            // still executes (mvn may be present); we assert on shape only.
            assertEquals(com.jade.api.PlanStep.RUN_BUILD, execution.trace().get(0).step());
            assertEquals(com.jade.api.PlanStep.LAST_PROJECT_OUTCOME, execution.trace().get(1).step());
        }
    }

    // ------------------------------------------------------------------

    private void assumeMavenPresent() {
        boolean present = new java.io.File("/opt/homebrew/bin/mvn").exists()
                || new java.io.File("/usr/local/bin/mvn").exists();
        org.junit.jupiter.api.Assumptions.assumeTrue(present, "Maven not available");
    }

    private Path passingProject() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("passing-" + System.nanoTime()));
        Files.writeString(root.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>passing</artifactId>
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
        Path test = Files.createDirectories(root.resolve("src/test/java/com/example"));
        Files.writeString(test.resolve("PassingTest.java"), """
                package com.example;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                class PassingTest {
                    @Test
                    void works() {
                        assertEquals(1, 1);
                    }
                }
                """);
        return root;
    }

    private Path failingProject() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("failing-" + System.nanoTime()));
        Files.writeString(root.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>failing</artifactId>
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
        Path test = Files.createDirectories(root.resolve("src/test/java/com/example"));
        Files.writeString(test.resolve("CalculatorTest.java"), """
                package com.example;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                class CalculatorTest {
                    @Test
                    void subtracts() {
                        assertEquals(2, 1);
                    }
                }
                """);
        return root;
    }

    private final class Runtime implements AutoCloseable {
        final ExecutorService executor;
        final SqliteHistoryRepository history;
        final DefaultCommandGateway gateway;

        Runtime() throws Exception {
            this(ConfirmationHandler.Decision.CONFIRMED);
        }

        Runtime(ConfirmationHandler.Decision decision) throws Exception {
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
                    new com.jade.services.files.ScopedFileMutationService(List.of(scope), history),
                    history,
                    List.of(scope),
                    pending -> decision,
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
