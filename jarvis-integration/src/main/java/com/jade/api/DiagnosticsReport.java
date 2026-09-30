package com.jade.api;

import java.util.List;
import java.util.Objects;

/**
 * Structured answer to "what failed" / "show me the errors": the bounded
 * diagnostics of the most recent project operation of this session. An empty
 * list with {@code ProjectOperationStatus#SUCCEEDED} means the last run had
 * zero detected failures — never a stand-in for an unrun or unknown state.
 */
public record DiagnosticsReport(
        ProjectOperationStatus lastStatus,
        List<Diagnostic> diagnostics,
        boolean truncated) implements CommandResult {
    /** Retention bound: at most this many diagnostics are kept. */
    public static final int MAX_DIAGNOSTICS = 25;

    public DiagnosticsReport {
        Objects.requireNonNull(lastStatus, "lastStatus");
        diagnostics = List.copyOf(diagnostics);
    }

    public static DiagnosticsReport noneDetected(ProjectOperationStatus lastStatus) {
        return new DiagnosticsReport(lastStatus, List.of(), false);
    }
}
