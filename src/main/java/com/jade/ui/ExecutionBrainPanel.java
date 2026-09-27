package com.jade.ui;

import com.jade.api.CommandOutcome;
import com.jade.api.CommandStatus;
import com.jade.api.ProgressEvent;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.util.Locale;

/**
 * The live execution brain: an always-visible strip that animates the
 * TOKENIZE → PARSE → PLAN → EXECUTE → DIAGNOSE pipeline while a command
 * runs. It is a dumb renderer — every state transition comes from the pure
 * {@link ExecutionStageModel}, driven by the gateway's real progress events,
 * so the visualization can never show a stage that did not actually happen.
 * Updates arrive from worker threads and are marshalled onto the JavaFX
 * Application Thread here.
 */
public final class ExecutionBrainPanel extends HBox {

    private final ExecutionStageModel model = new ExecutionStageModel();
    private final Label[] pills;
    private final HBox pillRow = new HBox(6);
    private final Label statusLabel = new Label("Idle — enter a command");
    private final Label planBadge = new Label("multi-step plan");

    public ExecutionBrainPanel() {
        ExecutionStageModel.BrainStage[] stages = ExecutionStageModel.BrainStage.values();
        pills = new Label[stages.length];
        for (int index = 0; index < stages.length; index += 1) {
            if (index > 0) {
                Region arrow = new Region();
                arrow.getStyleClass().add("brain-arrow");
                pillRow.getChildren().add(arrow);
            }
            Label pill = new Label(stages[index].name());
            pill.getStyleClass().add("stage-pill");
            pills[index] = pill;
            pillRow.getChildren().add(pill);
        }

        planBadge.getStyleClass().addAll("plan-badge", "hidden-node");
        planBadge.setManaged(false);
        statusLabel.getStyleClass().add("label-subtle");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        setSpacing(8);
        setPadding(new Insets(8, 0, 2, 0));
        getChildren().addAll(pillRow, planBadge, statusLabel);
        HBox.setHgrow(statusLabel, javafx.scene.layout.Priority.ALWAYS);
        getStyleClass().add("execution-pipeline");
        setVisible(false);
        setManaged(false);
        render();
    }

    /** Feeds one real gateway progress event into the pipeline. */
    public void onProgressEvent(ProgressEvent event) {
        if (event == null) {
            return;
        }
        onFxThread(() -> {
            model.onProgress(event.stage());
            if (!model.finished()) {
                statusLabel.setText(stageNarrative(event.stage(), event.message()));
            }
            render();
        });
    }

    /** Completes the pipeline with the real command outcome. */
    public void onOutcome(CommandOutcome outcome) {
        if (outcome == null) {
            return;
        }
        onFxThread(() -> {
            getStyleClass().add("pipeline-complete");
            pillRow.setVisible(false);
            pillRow.setManaged(false);
            model.onComplete(outcome.status());
            statusLabel.setText(switch (outcome.status()) {
                case SUCCEEDED -> "✓ " + outcome.summary();
                case FAILED -> "✗ Failed — " + outcome.summary();
                case REJECTED -> "⊘ Rejected — " + outcome.summary();
                case CANCELLED -> "– Cancelled";
            });
            render();
        });
    }

    /** Marks that this run executed a real typed multi-step plan. */
    public void markMultiStepPlan() {
        onFxThread(() -> {
            model.markMultiStepPlan();
            planBadge.setManaged(true);
            planBadge.setVisible(true);
        });
    }

    /** Resets the strip for a newly submitted command. */
    public void resetForNewCommand() {
        onFxThread(() -> {
            setVisible(true);
            setManaged(true);
            getStyleClass().remove("pipeline-complete");
            pillRow.setVisible(true);
            pillRow.setManaged(true);
            model.reset();
            planBadge.setManaged(false);
            planBadge.setVisible(false);
            statusLabel.setText("Running…");
            render();
        });
    }

    private static String stageNarrative(com.jade.api.ProgressStage stage, String message) {
        if (message == null || message.isBlank()) {
            return stage.name() + "…";
        }
        return switch (stage) {
            case AWAITING_CONFIRMATION -> "⏸ " + message;
            default -> message;
        };
    }

    private void render() {
        for (ExecutionStageModel.BrainStage stage : ExecutionStageModel.BrainStage.values()) {
            Label pill = pills[stage.ordinal()];
            ExecutionStageModel.StageState state = model.state(stage);
            pill.getStyleClass().removeIf(c -> c.startsWith("stage-") && !c.equals("stage-pill"));
            pill.getStyleClass().add("stage-" + state.name().toLowerCase(Locale.ROOT));
        }
    }

    private static void onFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
