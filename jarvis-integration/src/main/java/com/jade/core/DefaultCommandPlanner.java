package com.jade.core;

import com.jade.api.ExecutionPlan;
import com.jade.api.PlanStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The deterministic, bounded planner: recognises a small fixed set of
 * compound request shapes and emits the corresponding typed
 * {@link ExecutionPlan}. No arbitrary English decomposition — every
 * accepted shape is one literal branch below; anything else is refused and
 * the caller treats the text as a single command.
 */
public final class DefaultCommandPlanner implements com.jade.api.CommandPlanner {

    @Override
    public Optional<ExecutionPlan> plan(String request) {
        if (request == null || request.isBlank()) {
            return Optional.empty();
        }
        List<String> words = new ArrayList<>();
        for (String raw : request.strip().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (raw.endsWith("?") && raw.length() > 1) {
                raw = raw.substring(0, raw.length() - 1);
            }
            if (!raw.isEmpty()) {
                words.add(raw);
            }
        }
        List<PlanStep> steps = matchShapes(words);
        if (steps == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ExecutionPlan(steps));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Safe positional accessor: "" beyond the end of the phrase. */
    private static String word(List<String> words, int index) {
        return index < words.size() ? words.get(index) : "";
    }

    /**
     * The explicit compound grammar. Each accepted shape is one literal
     * branch returning its fixed step list; every other input falls through
     * to {@code null} ("not a compound command").
     *
     * Supported shapes:
     * <ul>
     *   <li>"inspect this/the project and run [the] tests"</li>
     *   <li>"inspect this/the project and build it"</li>
     *   <li>"run [the] tests and tell me what failed"</li>
     *   <li>"run [the] tests and show me the errors"</li>
     *   <li>"run [the] tests and what failed"</li>
     *   <li>"build it and tell me what happened"</li>
     *   <li>"build it and what happened"</li>
     *   <li>"run [the] tests and tell me what happened"</li>
     * </ul>
     */
    private List<PlanStep> matchShapes(List<String> words) {
        int size = words.size();
        if (size < 4 || size > 8) {
            return null;
        }
        String w0 = word(words, 0);

        // --- inspect ... and run/build ---
        if (w0.equals("inspect")
                && (word(words, 1).equals("this") || word(words, 1).equals("the"))
                && word(words, 2).equals("project") && word(words, 3).equals("and")) {
            // "inspect this project and run the tests" (7) / "… run tests" (6)
            if (word(words, 4).equals("run")) {
                boolean withThe = word(words, 5).equals("the") && word(words, 6).equals("tests");
                boolean withoutThe = word(words, 5).equals("tests");
                if (withThe || withoutThe) {
                    return List.of(PlanStep.INSPECT_PROJECT, PlanStep.RUN_TESTS);
                }
            }
            // "inspect this project and build it" (6)
            if (word(words, 4).equals("build") && word(words, 5).equals("it")) {
                return List.of(PlanStep.INSPECT_PROJECT, PlanStep.RUN_BUILD);
            }
            return null;
        }

        // --- run [the] tests and ... ---
        // "run tests and what failed|happened" (5) / "run the tests and what failed|happened" (6)
        boolean runTests = w0.equals("run")
                && (word(words, 1).equals("tests")
                        || (word(words, 1).equals("the") && word(words, 2).equals("tests")));
        int andIndex = word(words, 1).equals("tests") ? 2 : 3;
        if (runTests && word(words, andIndex).equals("and")) {
            String w4 = word(words, andIndex + 1);
            String w5 = word(words, andIndex + 2);
            String w6 = word(words, andIndex + 3);
            String w7 = word(words, andIndex + 4);
            if (w4.equals("what") && (w5.equals("failed") || w5.equals("happened"))) {
                return List.of(PlanStep.RUN_TESTS,
                        w5.equals("failed") ? PlanStep.DIAGNOSTICS : PlanStep.LAST_PROJECT_OUTCOME);
            }
            // "… and tell me what failed|happened" (3-token tails)
            if (w4.equals("tell") && w5.equals("me") && w6.equals("what")
                    && (w7.equals("failed") || w7.equals("happened"))) {
                return List.of(PlanStep.RUN_TESTS,
                        w7.equals("failed") ? PlanStep.DIAGNOSTICS : PlanStep.LAST_PROJECT_OUTCOME);
            }
            // "… and show me the errors"
            if (w4.equals("show") && w5.equals("me") && w6.equals("the") && w7.equals("errors")) {
                return List.of(PlanStep.RUN_TESTS, PlanStep.DIAGNOSTICS);
            }
            return null;
        }

        // --- build it ... ---
        if (w0.equals("build") && word(words, 1).equals("it") && word(words, 2).equals("and")) {
            String w3 = word(words, 3);
            String w4 = word(words, 4);
            String w5 = word(words, 5);
            String w6 = word(words, 6);
            // "build it and tell me what happened" (7 tokens)
            if (w3.equals("tell") && w4.equals("me") && w5.equals("what") && w6.equals("happened")) {
                return List.of(PlanStep.RUN_BUILD, PlanStep.LAST_PROJECT_OUTCOME);
            }
            // "build it and what happened" (5 tokens)
            if (w3.equals("what") && w4.equals("happened")) {
                return List.of(PlanStep.RUN_BUILD, PlanStep.LAST_PROJECT_OUTCOME);
            }
            return null;
        }
        return null;
    }
}
