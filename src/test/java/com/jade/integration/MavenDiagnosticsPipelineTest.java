package com.jade.integration;

import com.jade.api.BuildSystem;
import com.jade.api.CommandOutcome;
import com.jade.api.CommandRequest;
import com.jade.api.CommandResult;
import com.jade.api.CommandStatus;
import com.jade.api.ConfiguredApp;
import com.jade.api.Diagnostic;
import com.jade.api.DiagnosticsReport;
import com.jade.api.ErrorCode;
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
 * Real controlled diagnostics check (C7): a generated temporary Maven
 * project with one intentionally failing test is run through the production
 * Maven runner, and the failing test is then detected by the production
 * diagnostics extraction — through the same parser/gateway pipeline as the
 * UI would. Skipped automatically when Maven is not on PATH.
 */
class MavenDiagnosticsPipelineTest {
    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("UTC");

    @TempDir
    Path temporaryDirectory;

    @Test
    void realFailingTestIsRunAndThenDiagnosed() throws Exception {
        assumeMavenPresent();
        Path projectRoot = failingFixtureProject();
        try (Runtime runtime = new Runtime()) {
            runtime.openProject(projectRoot);

            // "run the tests" through the real pipeline and real Maven.
            ProjectOperationResult run = result(
                    runtime.submit("run the tests"), ProjectOperationResult.class);
            assertEquals(ProjectOperationStatus.BUILD_FAILED, run.status(),
                    "the fixture intentionally fails; Maven must report it");

            // "what failed" → structured diagnostics from real evidence.
            DiagnosticsReport report = result(
                    runtime.submit("what failed"), DiagnosticsReport.class);
            assertEquals(1, report.diagnostics().size(), report.toString());
            Diagnostic diagnostic = report.diagnostics().getFirst();
            assertEquals(Diagnostic.Kind.TEST_FAILURE, diagnostic.kind());
            assertEquals("com.example.CalculatorTest", diagnostic.testClass().orElseThrow());
            assertEquals("subtracts", diagnostic.testMethod().orElseThrow());
            assertTrue(diagnostic.message().orElseThrow().contains("expected"));

            // "how many tests failed" → the compact typed count.
            com.jade.api.DiagnosticCount count = result(
                    runtime.submit("how many tests failed"), com.jade.api.DiagnosticCount.class);
            assertEquals(1, count.failedTestCount());

            // Diagnostics refer to the last operation even when asked twice.
            DiagnosticsReport again = result(runtime.submit("what failed"), DiagnosticsReport.class);
            assertEquals(1, again.diagnostics().size());
        }
    }

    @Test
    void diagnosticsWithoutAPriorOperationIsAnHonestRejection() throws Exception {
        try (Runtime runtime = new Runtime()) {
            CommandOutcome outcome = runtime.submit("what failed");
            assertEquals(CommandStatus.REJECTED, outcome.status());
            assertEquals(ErrorCode.INVALID_COMMAND, outcome.error().orElseThrow().code());
        }
    }

    @Test
    void successfulRunThenDiagnosticsReportsZeroFailures() throws Exception {
        assumeMavenPresent();
        Path projectRoot = passingFixtureProject();
        try (Runtime runtime = new Runtime()) {
            runtime.openProject(projectRoot);
            result(runtime.submit("run the tests"), ProjectOperationResult.class);
            DiagnosticsReport report = result(runtime.submit("what failed"), DiagnosticsReport.class);
            assertTrue(report.diagnostics().isEmpty());
            assertEquals(ProjectOperationStatus.SUCCEEDED, report.lastStatus());
        }
    }

    // ------------------------------------------------------------------

    private void assumeMavenPresent() {
        String path = System.getenv("PATH");
        boolean present = new MavenProjectProcessRunner() != null
                && path != null
                && (new java.io.File("/opt/homebrew/bin/mvn").exists()
                        || new java.io.File("/usr/local/bin/mvn").exists()
                        || path.contains("maven"));
        org.junit.jupiter.api.Assumptions.assumeTrue(present, "Maven not available on PATH");
    }

    /** Minimal Maven project whose single test deliberately fails. */
    private Path failingFixtureProject() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("failing-demo"));
        Files.writeString(root.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>failing-demo</artifactId>
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
                    void adds() {
                        assertEquals(2, 1 + 1);
                    }

                    @Test
                    void subtracts() {
                        assertEquals(2, 1 - 1 + 1 - 1 + 1);
                    }
                }
                """);
        return root;
    }

    private Path passingFixtureProject() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("passing-demo"));
        Files.writeString(root.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>passing-demo</artifactId>
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
                    null, null, List.of(scope), null,
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
