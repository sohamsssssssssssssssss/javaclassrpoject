package com.jade.api;

import java.util.List;
import java.util.Objects;

/**
 * Typed result of one multi-step plan execution: the bounded ordered trace
 * (step, status, duration, result category) plus the results of succeeded
 * steps in plan order. There is deliberately no combined blob — later
 * consumers receive structured values.
 */
public record ExecutionResult(List<ExecutionStepResult> trace) implements CommandResult {
    public ExecutionResult {
        trace = List.copyOf(trace);
        Objects.requireNonNull(trace, "trace");
    }

    /** True when every executed step ended SUCCEEDED. */
    public boolean allStepsSucceeded() {
        return trace.stream().allMatch(step -> step.status() == ExecutionStepResult.StepStatus.SUCCEEDED);
    }
}
