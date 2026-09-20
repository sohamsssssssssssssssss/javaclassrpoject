package com.jade.core;

import com.jade.api.ExecutionPlan;
import com.jade.api.PlanStep;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The bounded compound grammar of the deterministic planner. */
class DefaultCommandPlannerTest {

    private final DefaultCommandPlanner planner = new DefaultCommandPlanner();

    @Test
    void supportedCompoundShapesMapToTypedPlans() {
        assertPlan("inspect this project and run the tests",
                PlanStep.INSPECT_PROJECT, PlanStep.RUN_TESTS);
        assertPlan("inspect the project and run tests",
                PlanStep.INSPECT_PROJECT, PlanStep.RUN_TESTS);
        assertPlan("inspect this project and build it",
                PlanStep.INSPECT_PROJECT, PlanStep.RUN_BUILD);
        assertPlan("run the tests and tell me what failed",
                PlanStep.RUN_TESTS, PlanStep.DIAGNOSTICS);
        assertPlan("run tests and what failed",
                PlanStep.RUN_TESTS, PlanStep.DIAGNOSTICS);
        assertPlan("run the tests and show me the errors",
                PlanStep.RUN_TESTS, PlanStep.DIAGNOSTICS);
        assertPlan("build it and tell me what happened",
                PlanStep.RUN_BUILD, PlanStep.LAST_PROJECT_OUTCOME);
        assertPlan("build it and what happened",
                PlanStep.RUN_BUILD, PlanStep.LAST_PROJECT_OUTCOME);
        assertPlan("run the tests and tell me what happened",
                PlanStep.RUN_TESTS, PlanStep.LAST_PROJECT_OUTCOME);
    }

    @Test
    void caseAndQuestionMarkAreTolerated() {
        assertPlan("Inspect this project and run the tests?",
                PlanStep.INSPECT_PROJECT, PlanStep.RUN_TESTS);
        assertPlan("BUILD IT AND WHAT HAPPENED",
                PlanStep.RUN_BUILD, PlanStep.LAST_PROJECT_OUTCOME);
    }

    @Test
    void arbitraryDecompositionIsRefused() {
        assertTrue(planner.plan("please inspect everything and then maybe run something").isEmpty());
        assertTrue(planner.plan("inspect the project and then deploy it").isEmpty());
        assertTrue(planner.plan("run the tests and fix what failed").isEmpty());
        assertTrue(planner.plan("build it and email me the result").isEmpty());
        assertTrue(planner.plan("clean the house and run the tests").isEmpty());
        assertTrue(planner.plan("what failed").isEmpty(), "single commands are not plans");
        assertTrue(planner.plan("").isEmpty());
        assertTrue(planner.plan(null).isEmpty());
    }

    @Test
    void nearMissTailsAreRejected() {
        assertTrue(planner.plan("run the tests and tell me why").isEmpty());
        assertTrue(planner.plan("build it and deploy").isEmpty());
        assertTrue(planner.plan("inspect this project and dance").isEmpty());
    }

    private void assertPlan(String input, PlanStep... expected) {
        Optional<ExecutionPlan> plan = planner.plan(input);
        assertTrue(plan.isPresent(), input);
        assertArrayEquals(expected, plan.get().steps().toArray(), input);
    }
}
