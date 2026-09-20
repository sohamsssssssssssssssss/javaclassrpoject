package com.jade.services.project;

import com.jade.api.BuildSystem;
import com.jade.api.ProjectContext;
import com.jade.api.ProjectOperation;
import com.jade.api.ProjectOperationResult;
import com.jade.api.ProjectOperationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 4B process-safety tests for the bounded Maven runner core. Uses a
 * fake {@link Process} so the wait/timeout/truncation logic is proven
 * deterministically without launching anything.
 */
class MavenProjectProcessRunnerTest {

    @TempDir
    Path temporaryDirectory;

    private ProjectContext project() {
        Path root = temporaryDirectory.resolve("demo-project");
        return new ProjectContext(root, "demo-project", BuildSystem.MAVEN, root.resolve("pom.xml"));
    }

    @Test
    void commandsMapOnlyToKnownMavenInvocations() {
        assertEquals(List.of("mvn", "test"), MavenProjectProcessRunner.commandFor(ProjectOperation.TEST));
        assertEquals(List.of("mvn", "package"),
                MavenProjectProcessRunner.commandFor(ProjectOperation.BUILD));
        // Exhaustive: the enum has exactly the two bounded operations.
        assertEquals(2, ProjectOperation.values().length);
    }

    @Test
    void zeroExitIsASuccessAndNonZeroIsAnHonestBuildFailure() throws Exception {
        ProjectContext project = project();
        ProjectOperationResult ok = new MavenProjectProcessRunner().run(
                new FakeProcess(0, "build success"), project, ProjectOperation.TEST);
        assertEquals(ProjectOperationStatus.SUCCEEDED, ok.status());
        assertEquals(0, ok.exitCode());
        assertFalse(ok.timedOut());
        assertTrue(ok.durationMillis() >= 0);
        assertTrue(ok.outputSummary().contains("build success"));

        ProjectOperationResult failed = new MavenProjectProcessRunner().run(
                new FakeProcess(1, "Tests run: 1, Failures: 1"), project, ProjectOperation.BUILD);
        assertEquals(ProjectOperationStatus.BUILD_FAILED, failed.status(),
                "a non-zero tool exit is a real project result, not a JADE crash");
        assertEquals(1, failed.exitCode());
        assertFalse(failed.timedOut());
    }

    @Test
    void unfinishedProcessIsStoppedAndReportedAsTimeout() throws Exception {
        FakeProcess hung = new FakeProcess(0, "still running");
        hung.neverFinishes = true;
        ProjectOperationResult result = new MavenProjectProcessRunner(1).run(
                hung, project(), ProjectOperation.TEST);
        assertEquals(ProjectOperationStatus.TIMED_OUT, result.status());
        assertTrue(result.timedOut());
        assertNull(result.exitCode());
        assertTrue(hung.destroyed, "a timed-out process must be forcibly stopped");
    }

    @Test
    void outputIsBoundedAndTruncationIsTruthful() throws Exception {
        StringBuilder flood = new StringBuilder();
        for (int i = 0; i < 2_000; i++) {
            flood.append("line ").append(i).append(" with padding\n");
        }
        ProjectOperationResult result = new MavenProjectProcessRunner().run(
                new FakeProcess(0, flood.toString()), project(), ProjectOperation.BUILD);
        assertTrue(result.outputTruncated(),
                "output beyond the cap must be reported as truncated");
        assertTrue(result.outputSummary().length() <= MavenProjectProcessRunner.OUTPUT_LIMIT_CHARS,
                "retained output must respect the bound");
        assertTrue(result.outputSummary().contains("line 1999"),
                "the kept output is the TAIL, which is where build errors live");

        ProjectOperationResult small = new MavenProjectProcessRunner().run(
                new FakeProcess(0, "tiny output"), project(), ProjectOperation.TEST);
        assertFalse(small.outputTruncated());
    }

    /** A controllable fake process: no I/O machinery, no real execution. */
    private static final class FakeProcess extends Process {
        private final int exitCode;
        private final byte[] output;
        boolean neverFinishes;
        boolean destroyed;

        FakeProcess(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(output);
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            throw new UnsupportedOperationException("not used by the runner core");
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !neverFinishes;
        }

        @Override
        public int exitValue() {
            if (neverFinishes) {
                throw new IllegalStateException("process has not exited");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            return this;
        }

        @Override
        public boolean isAlive() {
            return neverFinishes;
        }
    }
}
