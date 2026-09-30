package com.jade.api;

import java.util.List;
import java.util.Objects;

/**
 * A typed, ordered multi-step plan produced by the deterministic planner.
 * Every step is a {@link PlanStep} value; nothing else can be planned — no
 * free text, no arguments, no commands. Dependency validation (which steps
 * require which context) happens when the plan is built.
 */
public record ExecutionPlan(List<PlanStep> steps) {
    public ExecutionPlan {
        steps = List.copyOf(steps);
        Objects.requireNonNull(steps, "steps");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("An execution plan must have at least one step");
        }
        if (steps.size() > 5) {
            throw new IllegalArgumentException("An execution plan is bounded to five steps");
        }
    }
}
