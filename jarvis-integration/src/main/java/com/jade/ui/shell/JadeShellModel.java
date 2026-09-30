package com.jade.ui.shell;

import com.jade.services.audio.VoiceState;
import com.jade.api.ProgressStage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure state-view model of the visual shell. It maps the real application
 * state (the existing {@link VoiceState}) onto visual core motion, truthful
 * status chips, and navigation state — without any JavaFX dependency, so the
 * whole shell behavior is unit-testable. The shell view binds to this model;
 * it never invents activity that the model did not report.
 */
public final class JadeShellModel {

    /** Shortcuts are exact phrases accepted by the existing command parser. */
    public enum Suggestion {
        SYSTEM("System status", "system status"),
        FILES("Find PDFs", "find pdfs"),
        PROJECT("Inspect project", "give me a project summary");

        private final String label;
        private final String command;
        Suggestion(String label, String command) { this.label = label; this.command = command; }
        public String label() { return label; }
        public String command() { return command; }
    }

    /** The five fixed navigation destinations of the V1 shell. */
    public enum Destination {
        HOME,
        FILES,
        PROJECT,
        ACTIVITY,
        SYSTEM
    }

    private Destination navigation = Destination.HOME;
    private VoiceState appState = VoiceState.IDLE;
    private VoiceState voiceState = VoiceState.IDLE;

    public void setVoiceState(VoiceState state) {
        voiceState = Objects.requireNonNull(state, "state");
    }

    /** Truthful status chips for the top status area, in display order. */
    public record StatusChip(String name, String value, String tone) {
        public StatusChip {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(tone, "tone");
        }
    }

    /** Applies one real application state (voice stack / command pipeline). */
    public void setAppState(VoiceState state) {
        this.appState = state == null ? VoiceState.IDLE : state;
    }

    /** The real application state currently reflected by the shell. */
    public VoiceState appState() {
        return appState;
    }

    /** Motion profile for the current real state (delegates, never invents). */
    public CoreMotionProfile motionProfile() {
        return CoreMotionProfile.forState(appState);
    }

    /** Gateway progress is real; planning and execution have distinct visible states. */
    public static VoiceState forProgress(ProgressStage stage) {
        return switch (Objects.requireNonNull(stage, "stage")) {
            case QUEUED, PARSING, PLANNING -> VoiceState.PROCESSING;
            case AWAITING_CONFIRMATION, EXECUTING, PERSISTING -> VoiceState.EXECUTING;
        };
    }

    /**
     * Reflects the project name the shell has actually observed — a typed
     * {@code ProjectContext} result already rendered by the command center —
     * or {@code null} when no project is active. The shell never guesses a
     * project: the label comes only from this observation.
     */
    public void setObservedProject(String name) {
        this.observedProject = (name == null || name.isBlank()) ? null : name;
    }

    /** The observed project name, or null when none is active. */
    public String observedProject() {
        return observedProject;
    }

    /**
     * Truthful top-status chips derived from the current state. There is no
     * voice-unavailable distinct value at the model level: callers report
     * voice availability through {@link #setVoiceAvailable(boolean)}.
     */
    public List<StatusChip> statusChips() {
        List<StatusChip> chips = new ArrayList<>();
        if (voiceAvailable) {
            chips.add(new StatusChip("VOICE", voiceState == VoiceState.IDLE ? "READY" : voiceState.toString(), toneForState(voiceState)));
        } else {
            chips.add(new StatusChip("VOICE", "UNAVAILABLE", "muted"));
        }
        chips.add(new StatusChip("PROJECT", observedProject == null ? "NONE" : observedProject.toUpperCase(java.util.Locale.ROOT),
                observedProject == null ? "muted" : "accent"));
        chips.add(new StatusChip("LOCAL", "ONLY", "muted"));
        return chips;
    }

    private static String toneForState(VoiceState state) {
        return switch (state) {
            case ERROR -> "error";
            case LISTENING, PROCESSING, EXECUTING, SPEAKING -> "accent";
            case IDLE -> "muted";
        };
    }

    private boolean voiceAvailable = true;
    private String observedProject;

    /** Reflects whether the voice stack actually loaded (honesty seam). */
    public void setVoiceAvailable(boolean voiceAvailable) {
        this.voiceAvailable = voiceAvailable;
    }

    /** Current navigation destination. */
    public Destination navigation() {
        return navigation;
    }

    /** Navigates to a destination (deterministic, no side effects). */
    public void navigate(Destination destination) {
        this.navigation = Objects.requireNonNull(destination, "destination");
    }

}
