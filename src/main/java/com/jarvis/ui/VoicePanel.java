package com.jarvis.ui;

import com.jarvis.services.audio.SpeakerIdentity;
import com.jarvis.services.audio.VoiceState;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Minimal voice status strip: a start button plus the IDLE/LISTENING/
 * PROCESSING/EXECUTING/SPEAKING/ERROR state, the last recognized transcript
 * and the identified speaker. Added alongside the existing layout without
 * redesigning it. All updates arrive from the voice worker thread and are
 * marshalled onto the JavaFX Application Thread here, so voice processing
 * can never freeze or block UI rendering.
 */
public final class VoicePanel extends HBox {

    private final Label stateLabel = new Label(VoiceState.IDLE.toString());
    private final Label transcriptLabel = new Label("");
    private final Label speakerLabel = new Label("");
    private final Button startButton = new Button("Start voice");

    private volatile Consumer<VoiceState> externalStateConsumer;
    private final Consumer<VoiceState> stateConsumer;
    private final Consumer<String> transcriptConsumer;
    private final Consumer<String> spokenConsumer;
    private final Consumer<SpeakerIdentity> identityConsumer;

    public VoicePanel() {
        stateLabel.getStyleClass().add("label-subtle");
        transcriptLabel.getStyleClass().add("text-primary");
        transcriptLabel.setWrapText(true);
        transcriptLabel.setMaxWidth(Double.MAX_VALUE);
        speakerLabel.getStyleClass().add("label-subtle");
        HBox.setHgrow(transcriptLabel, Priority.ALWAYS);

        startButton.getStyleClass().add("button-secondary");
        startButton.setDisable(false);
        startButton.setOnAction(ignored -> { /* wired by the app via onStart */ });

        setSpacing(8);
        setPadding(new Insets(8, 0, 0, 0));
        getChildren().addAll(startButton, stateLabel, transcriptLabel, speakerLabel);

        stateConsumer = state -> Platform.runLater(() -> {
            if (state == null) {
                stateLabel.setText("Voice unavailable (STT model not installed)");
                startButton.setDisable(true);
                return;
            }
            stateLabel.setText(state.toString());
            stateLabel.getStyleClass().removeIf(c -> c.startsWith("voice-state-"));
            stateLabel.getStyleClass().add("voice-state-" + state.toString().toLowerCase(java.util.Locale.ROOT));
            startButton.setDisable(state != VoiceState.IDLE && state != VoiceState.ERROR);
            Consumer<VoiceState> external = externalStateConsumer;
            if (external != null) {
                external.accept(state);
            }
        });
        transcriptConsumer = text -> Platform.runLater(() ->
                transcriptLabel.setText(text == null || text.isBlank() ? "" : "“" + text.strip() + "”"));
        spokenConsumer = text -> Platform.runLater(() -> {
            if (text != null && !text.isBlank()) {
                transcriptLabel.setText("🔊 " + text.strip());
            }
        });
        identityConsumer = identity -> Platform.runLater(() -> {
            if (identity == null || identity == SpeakerIdentity.UNKNOWN) {
                speakerLabel.setText("");
            } else {
                speakerLabel.setText("Speaker: " + identity);
            }
        });
    }

    /** Subscribes an additional observer of voice states (used by tests). */
    public void onExternalState(Consumer<VoiceState> observer) {
        this.externalStateConsumer = Objects.requireNonNull(observer, "observer");
    }

    /** Callback for the controller's voice-state transitions (thread-safe). */
    public Consumer<VoiceState> stateConsumer() {
        return Objects.requireNonNull(stateConsumer);
    }

    /** Callback for recognized transcripts (thread-safe). */
    public Consumer<String> transcriptConsumer() {
        return Objects.requireNonNull(transcriptConsumer);
    }

    /** Callback for text JARVIS speaks aloud (thread-safe). */
    public Consumer<String> spokenConsumer() {
        return Objects.requireNonNull(spokenConsumer);
    }

    /** Callback for the identified speaker (thread-safe). */
    public Consumer<SpeakerIdentity> identityConsumer() {
        return Objects.requireNonNull(identityConsumer);
    }

    /** Wires the start button to the app-provided session starter. */
    public void onStart(Runnable starter) {
        Objects.requireNonNull(starter, "starter");
        startButton.setOnAction(ignored -> starter.run());
    }
}
