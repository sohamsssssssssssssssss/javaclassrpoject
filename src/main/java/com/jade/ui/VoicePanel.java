package com.jade.ui;

import com.jade.services.audio.SpeakerIdentity;
import com.jade.services.audio.VoiceState;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The voice experience strip: start button, static listening indicator,
 * the IDLE/LISTENING/PROCESSING/EXECUTING/SPEAKING/ERROR state, the last
 * recognized transcript, and the identified speaker. Added alongside the
 * existing layout without redesigning it. All updates arrive from the voice
 * worker thread and are marshalled onto the JavaFX Application Thread here,
 * so voice processing can never freeze or block UI rendering.
 */
public final class VoicePanel extends HBox {

    private static final int EQUALIZER_BARS = 5;

    private final Label stateLabel = new Label(VoiceState.IDLE.toString());
    private final Label transcriptLabel = new Label("");
    private final Label speakerLabel = new Label("");
    private final Region[] equalizerBars = new Region[EQUALIZER_BARS];
    private final Button startButton = new Button("Start voice");

    private volatile Consumer<VoiceState> externalStateConsumer;
    private final Consumer<VoiceState> stateConsumer;
    private final Consumer<String> transcriptConsumer;
    private final Consumer<String> spokenConsumer;
    private final Consumer<SpeakerIdentity> identityConsumer;

    public VoicePanel() {
        for (int index = 0; index < EQUALIZER_BARS; index += 1) {
            Region bar = new Region();
            bar.getStyleClass().add("eq-bar");
            bar.setMinSize(3, 4);
            bar.setMaxSize(3, 16);
            bar.setOpacity(0.25);
            equalizerBars[index] = bar;
        }
        HBox equalizer = new HBox(2, equalizerBars);
        equalizer.setPadding(new Insets(0, 2, 0, 2));

        stateLabel.getStyleClass().add("label-subtle");
        transcriptLabel.getStyleClass().add("text-primary");
        transcriptLabel.setWrapText(true);
        transcriptLabel.setMaxWidth(Double.MAX_VALUE);
        speakerLabel.getStyleClass().add("speaker-chip");
        speakerLabel.setVisible(false);
        speakerLabel.setManaged(false);
        HBox.setHgrow(transcriptLabel, Priority.ALWAYS);

        startButton.getStyleClass().add("microphone-button");
        javafx.scene.shape.SVGPath microphone = new javafx.scene.shape.SVGPath();
        microphone.setContent("M8 1 C6.3 1 5 2.3 5 4 V9 C5 10.7 6.3 12 8 12 C9.7 12 11 10.7 11 9 V4 C11 2.3 9.7 1 8 1 Z M2 7 V9 C2 12.3 4.7 15 8 15 C11.3 15 14 12.3 14 9 V7 M8 15 V19 M4 19 H12");
        microphone.getStyleClass().add("microphone-icon");
        startButton.setText("");
        startButton.setGraphic(microphone);
        startButton.setAccessibleText("Start voice session");
        startButton.setTooltip(new javafx.scene.control.Tooltip("Start voice session"));
        startButton.setDisable(false);
        startButton.setOnAction(ignored -> { /* wired by the app via onStart */ });

        setSpacing(8);
        setPadding(new Insets(8, 0, 0, 0));
        getChildren().addAll(startButton, equalizer, stateLabel, transcriptLabel, speakerLabel);

        stateConsumer = state -> Platform.runLater(() -> {
            if (state == null) {
                stateLabel.setText("Voice unavailable");
                setVisible(false);
                setManaged(false);
                startButton.setTooltip(new javafx.scene.control.Tooltip("Voice unavailable · speech model not installed"));
                startButton.setDisable(true);
                animateFor(VoiceState.IDLE);
                Consumer<VoiceState> external = externalStateConsumer;
                if (external != null) external.accept(null);
                return;
            }
            setVisible(state != VoiceState.IDLE);
            setManaged(state != VoiceState.IDLE);
            stateLabel.setText(state.toString());
            stateLabel.getStyleClass().removeIf(c -> c.startsWith("voice-state-"));
            stateLabel.getStyleClass().add("voice-state-" + state.toString().toLowerCase(java.util.Locale.ROOT));
            startButton.setDisable(state != VoiceState.IDLE && state != VoiceState.ERROR);
            animateFor(state);
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
                speakerLabel.setVisible(false);
                speakerLabel.setManaged(false);
            } else {
                speakerLabel.setText("Speaker: " + identity);
                speakerLabel.setVisible(true);
                speakerLabel.setManaged(true);
            }
        });
    }

    public Button detachMicrophone() {
        getChildren().remove(startButton);
        return startButton;
    }

    /** Static indicators only: microphone levels are not available, so none are fabricated. */
    private void animateFor(VoiceState state) {
        for (Region bar : equalizerBars) bar.setOpacity(state == VoiceState.LISTENING ? 0.8 : 0.25);
        getStyleClass().remove("voice-speaking");
        if (state == VoiceState.SPEAKING) getStyleClass().add("voice-speaking");
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

    /** Callback for text JADE speaks aloud (thread-safe). */
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
