package com.jade.ui;

import com.jade.api.CommandStatus;
import com.jade.api.ProgressStage;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the pure pipeline state machine behind the execution brain. */
class ExecutionStageModelTest {

    @Test
    void freshModelHasTokenizeActiveAndEverythingElsePending() {
        ExecutionStageModel model = new ExecutionStageModel();
        assertEquals(ExecutionStageModel.StageState.ACTIVE, model.state(ExecutionStageModel.BrainStage.TOKENIZE));
        for (ExecutionStageModel.BrainStage stage : ExecutionStageModel.BrainStage.values()) {
            if (stage != ExecutionStageModel.BrainStage.TOKENIZE) {
                assertEquals(ExecutionStageModel.StageState.PENDING, model.state(stage), stage.name());
            }
        }
        assertFalse(model.finished());
    }

    @Test
    void queuedEventKeepsTokenizeActive() {
        ExecutionStageModel model = new ExecutionStageModel();
        model.onProgress(ProgressStage.QUEUED);
        assertEquals(ExecutionStageModel.BrainStage.TOKENIZE, model.activeStage());
        assertEquals(ExecutionStageModel.StageState.ACTIVE, model.state(ExecutionStageModel.BrainStage.TOKENIZE));
    }

    @Test
    void parsingEventCompletesTokenizeAndActivatesParse() {
        ExecutionStageModel model = new ExecutionStageModel();
        model.onProgress(ProgressStage.PARSING);
        assertEquals(ExecutionStageModel.BrainStage.PARSE, model.activeStage());
        assertEquals(ExecutionStageModel.StageState.DONE, model.state(ExecutionStageModel.BrainStage.TOKENIZE));
        assertEquals(ExecutionStageModel.StageState.ACTIVE, model.state(ExecutionStageModel.BrainStage.PARSE));
        assertEquals(ExecutionStageModel.StageState.PENDING, model.state(ExecutionStageModel.BrainStage.PLAN));
    }

    @Test
    void fullHappyPathEndsWithAllStagesDone() {
        ExecutionStageModel model = new ExecutionStageModel();
        model.onProgress(ProgressStage.QUEUED);
        model.onProgress(ProgressStage.PARSING);
        model.onProgress(ProgressStage.PLANNING);
        model.onProgress(ProgressStage.EXECUTING);
        model.onProgress(ProgressStage.PERSISTING);
        for (ExecutionStageModel.BrainStage stage : ExecutionStageModel.BrainStage.values()) {
            if (stage == ExecutionStageModel.BrainStage.DIAGNOSE) {
                assertEquals(ExecutionStageModel.StageState.ACTIVE, model.state(stage), stage.name());
            } else {
                assertEquals(ExecutionStageModel.StageState.DONE, model.state(stage), stage.name());
            }
        }
        assertEquals(ExecutionStageModel.BrainStage.DIAGNOSE, model.activeStage());
        model.onComplete(CommandStatus.SUCCEEDED);
        for (ExecutionStageModel.BrainStage stage : ExecutionStageModel.BrainStage.values()) {
            assertEquals(ExecutionStageModel.StageState.DONE, model.state(stage), stage.name());
        }
        assertEquals(CommandStatus.SUCCEEDED, model.finalStatus());
        assertTrue(model.finished());
        assertNull(model.activeStage());
    }

    @Test
    void awaitingConfirmationMarksExecuteStageAndFlag() {
        ExecutionStageModel model = new ExecutionStageModel();
        model.onProgress(ProgressStage.AWAITING_CONFIRMATION);
        assertEquals(ExecutionStageModel.BrainStage.EXECUTE, model.activeStage());
        assertTrue(model.awaitingConfirmation());
        model.onProgress(ProgressStage.EXECUTING);
        assertFalse(model.awaitingConfirmation());
    }

    @Test
    void completingEarlyMarksAllStagesDone() {
        ExecutionStageModel model = new ExecutionStageModel();
        model.onProgress(ProgressStage.PARSING);
        model.onComplete(CommandStatus.REJECTED);
        for (ExecutionStageModel.BrainStage stage : ExecutionStageModel.BrainStage.values()) {
            assertEquals(ExecutionStageModel.StageState.DONE, model.state(stage), stage.name());
        }
        assertEquals(CommandStatus.REJECTED, model.finalStatus());
    }

    @Test
    void eventsAfterCompletionAreIgnored() {
        ExecutionStageModel model = new ExecutionStageModel();
        model.onComplete(CommandStatus.FAILED);
        model.onProgress(ProgressStage.PARSING);
        assertTrue(model.finished());
        assertNull(model.activeStage());
    }

    @Test
    void resetReturnsToFreshState() {
        ExecutionStageModel model = new ExecutionStageModel();
        model.onProgress(ProgressStage.EXECUTING);
        model.onComplete(CommandStatus.SUCCEEDED);
        model.markMultiStepPlan();
        model.reset();
        assertEquals(ExecutionStageModel.StageState.ACTIVE, model.state(ExecutionStageModel.BrainStage.TOKENIZE));
        assertEquals(ExecutionStageModel.StageState.PENDING, model.state(ExecutionStageModel.BrainStage.DIAGNOSE));
        assertFalse(model.multiStepPlan());
        assertFalse(model.finished());
    }

    @Test
    void nullEventsAreIgnored() {
        ExecutionStageModel model = new ExecutionStageModel();
        model.onProgress(null);
        model.onComplete(null);
        assertEquals(ExecutionStageModel.BrainStage.TOKENIZE, model.activeStage());
        assertFalse(model.finished());
    }
}
