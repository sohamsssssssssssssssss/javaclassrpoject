package com.jade.services.project;

import com.jade.api.ErrorCode;
import com.jade.api.ProjectContext;
import com.jade.api.ProjectOperation;
import com.jade.api.ProjectOperationResult;
import com.jade.api.ProjectOperationStatus;
import com.jade.api.ProjectProcessRunner;
import com.jade.api.ServiceException;
import com.jade.api.StructuredError;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Runs Maven for one typed operation inside the active project.
 *
 * <p>Process safety: the command line is always {@code mvn <fixed-arg>} for
 * the given {@link ProjectOperation} — {@code mvn test} for {@code TEST} and
 * {@code mvn package} for {@code BUILD}. No user text, no shell, no
 * interpolation. The working directory is the validated project root.</p>
 *
 * <p>Boundedness: the wait is capped by a timeout, after which the process
 * is destroyed and reported as {@code TIMED_OUT}; captured output is kept as
 * a bounded tail (oldest lines dropped first) and truncation is reported
 * truthfully rather than silently dropping content.</p>
 */
public final class MavenProjectProcessRunner implements ProjectProcessRunner {

    /** Reasonable default cap for a full Maven test/package run. */
    public static final long DEFAULT_TIMEOUT_SECONDS = 600;
    /** Bounded retained output: the last 20,000 characters. */
    public static final int OUTPUT_LIMIT_CHARS = 20_000;

    private final long timeoutSeconds;

    public MavenProjectProcessRunner() {
        this(DEFAULT_TIMEOUT_SECONDS);
    }

    public MavenProjectProcessRunner(long timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    /** The complete, fixed command for one typed operation — nothing else. */
    static List<String> commandFor(ProjectOperation operation) {
        return switch (operation) {
            case TEST -> List.of("mvn", "test");
            case BUILD -> List.of("mvn", "package");
        };
    }

    @Override
    public ProjectOperationResult execute(ProjectContext project, ProjectOperation operation)
            throws ServiceException {
        ProcessBuilder builder = new ProcessBuilder(commandFor(operation));
        builder.directory(project.root().toFile());
        builder.redirectErrorStream(true);
        long startedNanos = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.IO_FAILURE,
                    "Could not start Maven for " + operation.name().toLowerCase(java.util.Locale.ROOT)
                            + " in " + project.root(),
                    Optional.ofNullable(e.getMessage())));
        }
        return run(process, project, operation);
    }

    /** The bounded wait/capture/outcome core, separated for deterministic tests. */
    ProjectOperationResult run(Process process, ProjectContext project, ProjectOperation operation)
            throws ServiceException {
        long startedNanos = System.nanoTime();
        OutputCapture capture = new OutputCapture();
        Thread reader = new Thread(capture.consume(process), "jade-project-output");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ServiceException(new StructuredError(
                    ErrorCode.SERVICE_FAILURE, "Project operation was interrupted",
                    Optional.empty()));
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            reader.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long durationMillis = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        ProjectOperationStatus status;
        Integer exitCode;
        if (finished) {
            exitCode = process.exitValue();
            status = exitCode == 0 ? ProjectOperationStatus.SUCCEEDED : ProjectOperationStatus.BUILD_FAILED;
        } else {
            exitCode = null;
            status = ProjectOperationStatus.TIMED_OUT;
        }
        return new ProjectOperationResult(
                operation,
                project.name(),
                project.root(),
                status,
                exitCode,
                durationMillis,
                capture.summary(),
                capture.truncated(),
                !finished);
    }

    /** Bounded tail capture: keeps the newest lines, drops the oldest. */
    static final class OutputCapture {
        private final Deque<String> lines = new ArrayDeque<>();
        private int keptChars = 0;
        private long totalChars = 0;

        Runnable consume(Process process) {
            return () -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        record(line);
                    }
                } catch (IOException ignored) {
                    // The stream ends when the process exits or is destroyed.
                }
            };
        }

        private synchronized void record(String line) {
            totalChars += line.length() + 1;
            lines.addLast(line);
            keptChars += line.length() + 1;
            while (keptChars > OUTPUT_LIMIT_CHARS && lines.size() > 1) {
                String dropped = lines.removeFirst();
                keptChars -= dropped.length() + 1;
            }
        }

        synchronized String summary() {
            return String.join("\n", lines);
        }

        synchronized boolean truncated() {
            return totalChars > OUTPUT_LIMIT_CHARS;
        }
    }
}
