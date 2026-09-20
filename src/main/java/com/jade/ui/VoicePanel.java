package com.jade.ui;

import com.jade.services.audio.SpeakerIdentity;
import com.jade.services.audio.VoiceState;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The voice experience strip: start button, live equalizer while listening,
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
    private final Timeline listeningTimeline;
    private final Timeline speakingTimeline;
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

        startButton.getStyleClass().add("button-secondary");
        startButton.setDisable(false);
        startButton.setOnAction(ignored -> { /* wired by the app via onStart */ });

        setSpacing(8);
        setPadding(new Insets(8, 0, 0, 0));
        getChildren().addAll(startButton, equalizer, stateLabel, transcriptLabel, speakerLabel);

        // Equalizer: randomized bar levels on one steady tick while listening.
        listeningTimeline = new Timeline(new KeyFrame(Duration.millis(140), event -> {
            for (Region bar : equalizerBars) {
                double level = 0.3 + 0.7 * Math.random();
                bar.setScaleY(level);
                bar.setOpacity(0.35 + 0.65 * level);
            }
        }));
        listeningTimeline.setCycleCount(Animation.INDEFINITE);

        // Speaking pulse: gentle opacity breathing on the whole strip.
        speakingTimeline = new Timeline(
                new KeyFrame(Duration.millis(0), event -> setOpacity(1.0)),
                new KeyFrame(Duration.millis(300), event -> setOpacity(0.72)),
                new KeyFrame(Duration.millis(600), event -> setOpacity(1.0)));
        speakingTimeline.setCycleCount(Animation.INDEFINITE);

        stateConsumer = state -> Platform.runLater(() -> {
            if (state == null) {
                stateLabel.setText("Voice unavailable (STT model not installed)");
                startButton.setDisable(true);
                stopAnimations();
                return;
            }
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

    /** Starts or stops the equalizer / speaking animations for the state. */
    private void animateFor(VoiceState state) {
        boolean listening = state == VoiceState.LISTENING;
        boolean speaking = state == VoiceState.SPEAKING;
        if (listening && listeningTimeline.getStatus() != Animation.Status.RUNNING) {
            listeningTimeline.playFromStart();
        } else if (!listening) {
            listeningTimeline.stop();
            for (Region bar : equalizerBars) {
                bar.setScaleY(1.0);
                bar.setOpacity(0.25);
            }
        }
        if (speaking) {
            if (speakingTimeline.getStatus() != Animation.Status.RUNNING) {
                speakingTimeline.playFromStart();
            }
            getStyleClass().add("voice-speaking");
        } else {
            speakingTimeline.stop();
            setOpacity(1.0);
            getStyleClass().remove("voice-speaking");
        }
    }

    private void stopAnimations() {
        listeningTimeline.stop();
        speakingTimeline.stop();
        setOpacity(1.0);
        getStyleClass().remove("voice-speaking");
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
