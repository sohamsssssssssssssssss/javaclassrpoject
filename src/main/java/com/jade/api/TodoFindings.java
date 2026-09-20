package com.jade.api;

import java.util.List;
import java.util.Objects;

/**
 * Renderable TODO/FIXME findings of the active project, with the truthful
 * truncation flag: {@code truncated} is set when the retention bound
 * {@link ProjectInspectionService#MAX_TODO_FINDINGS} was reached.
 */
public record TodoFindings(List<TodoFinding> findings, boolean truncated) implements CommandResult {
    public TodoFindings {
        findings = List.copyOf(findings);
        Objects.requireNonNull(findings, "findings");
    }

    public static TodoFindings none() {
        return new TodoFindings(List.of(), false);
    }
}
