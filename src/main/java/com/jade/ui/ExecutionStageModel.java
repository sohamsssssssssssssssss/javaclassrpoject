package com.jade.ui;

import com.jade.api.CommandStatus;
import com.jade.api.ProgressStage;

import java.util.EnumSet;
import java.util.Set;

/**
 * Pure state machine behind the live execution brain. It maps the gateway's
 * {@link ProgressStage} events onto the five visible pipeline stages
 * (TOKENIZE → PARSE → PLAN → EXECUTE → DIAGNOSE) so the JavaFX panel stays a
 * dumb renderer. No JavaFX types here, so the mapping is unit-testable.
 */
public final class ExecutionStageModel {

    /** The five pipeline stages shown to the operator, in order. */
    public enum BrainStage {
        TOKENIZE,
        PARSE,
        PLAN,
        EXECUTE,
        DIAGNOSE
    }

    /** State of one pipeline stage while a command is running. */
    public enum StageState {
        PENDING,
        ACTIVE,
        DONE,
        SKIPPED
    }

    private static final Set<ProgressStage> TOKENIZE_STAGES = EnumSet.of(ProgressStage.QUEUED);
    private static final Set<ProgressStage> PARSE_STAGES = EnumSet.of(ProgressStage.PARSING);
    private static final Set<ProgressStage> PLAN_STAGES = EnumSet.of(ProgressStage.PLANNING);
    private static final Set<ProgressStage> EXECUTE_STAGES =
            EnumSet.of(ProgressStage.EXECUTING, ProgressStage.AWAITING_CONFIRMATION);
    private static final Set<ProgressStage> DIAGNOSE_STAGES = EnumSet.of(ProgressStage.PERSISTING);

    private BrainStage activeStage;
    private final StageState[] states;
    private boolean awaitingConfirmation;
    private boolean multiStepPlan;
    private CommandStatus finalStatus;
    private boolean finished;

    public ExecutionStageModel() {
        this.states = new StageState[BrainStage.values().length];
        reset();
    }

    /** Returns to the fresh, all-pending state for a new command. */
    public void reset() {
        for (int index = 0; index < states.length; index += 1) {
            states[index] = StageState.PENDING;
        }
        activeStage = BrainStage.TOKENIZE;
        states[activeStage.ordinal()] = StageState.ACTIVE;
        awaitingConfirmation = false;
        multiStepPlan = false;
        finalStatus = null;
        finished = false;
    }

    /** Applies one gateway progress event to the pipeline state. */
    public void onProgress(ProgressStage stage) {
        if (finished || stage == null) {
            return;
        }
        BrainStage target = brainStageFor(stage);
        if (target == null) {
            return;
        }
        // Every stage before the target is complete; the target is active.
        for (int index = 0; index < target.ordinal(); index += 1) {
            if (states[index] != StageState.DONE) {
                states[index] = StageState.DONE;
            }
        }
        states[target.ordinal()] = StageState.ACTIVE;
        // A later stage invalidates an earlier "active" marker.
        for (int index = target.ordinal() + 1; index < states.length; index += 1) {
            states[index] = StageState.PENDING;
        }
        activeStage = target;
        if (stage == ProgressStage.AWAITING_CONFIRMATION) {
            awaitingConfirmation = true;
        } else if (stage == ProgressStage.EXECUTING) {
            awaitingConfirmation = false;
        }
    }

    /** Completes the pipeline with the command's final status. */
    public void onComplete(CommandStatus status) {
        if (status == null) {
            return;
        }
        for (int index = 0; index < states.length; index += 1) {
            states[index] = StageState.DONE;
        }
        activeStage = null;
        finalStatus = status;
        finished = true;
    }

    /** Marks that the gateway produced a real multi-step plan (typed trace). */
    public void markMultiStepPlan() {
        multiStepPlan = true;
    }

    private static BrainStage brainStageFor(ProgressStage stage) {
        if (TOKENIZE_STAGES.contains(stage)) return BrainStage.TOKENIZE;
        if (PARSE_STAGES.contains(stage)) return BrainStage.PARSE;
        if (PLAN_STAGES.contains(stage)) return BrainStage.PLAN;
        if (EXECUTE_STAGES.contains(stage)) return BrainStage.EXECUTE;
        if (DIAGNOSE_STAGES.contains(stage)) return BrainStage.DIAGNOSE;
        return null;
    }

    public BrainStage activeStage() {
        return activeStage;
    }

    /** State of one visible stage; index must be a valid {@link BrainStage} ordinal. */
    public StageState state(BrainStage stage) {
        return states[stage.ordinal()];
    }

    public boolean awaitingConfirmation() {
        return awaitingConfirmation;
    }

    public boolean multiStepPlan() {
        return multiStepPlan;
    }

    public CommandStatus finalStatus() {
        return finalStatus;
    }

    public boolean finished() {
        return finished;
    }
}
