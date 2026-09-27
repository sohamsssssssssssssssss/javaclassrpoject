package com.jade.ui.shell;

import com.jade.services.audio.VoiceState;

import java.util.Objects;

/**
 * Visual behavior parameters for one real application state. The state type
 * itself is the existing {@link VoiceState} (IDLE/LISTENING/PROCESSING/
 * EXECUTING/SPEAKING/ERROR) — no competing enum. This record only describes
 * how the visual core should move for that state; it never invents activity:
 * states arrive from the real voice stack and command pipeline via the shell.
 *
 * @param state            the real application state this profile describes
 * @param stateLabel       short truthful label shown under the core
 * @param rotationStepDeg  degrees added per rotation tick (0 = no rotation)
 * @param breatheAmplitude relative breathing amplitude (0..1)
 * @param listeningPulse   whether the ring pulse emphasizes the microphone
 * @param waveform         whether the waveform layer is active
 * @param settle           whether motion stops (ERROR)
 */
public record CoreMotionProfile(
        VoiceState state,
        String stateLabel,
        double rotationStepDeg,
        double breatheAmplitude,
        boolean listeningPulse,
        boolean waveform,
        boolean settle) {

    public CoreMotionProfile {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(stateLabel, "stateLabel");
        if (breatheAmplitude < 0 || breatheAmplitude > 1) {
            throw new IllegalArgumentException("breatheAmplitude must be within 0..1");
        }
    }

    /** Static segment emphasis also remains legible when motion is reduced. */
    public int emphasizedSegments() {
        return switch (state) {
            case IDLE -> 1;
            case LISTENING -> 2;
            case PROCESSING -> 7;
            case EXECUTING -> 4;
            case SPEAKING, ERROR -> 0;
        };
    }

    /** Deterministic profile for every real state. */
    public static CoreMotionProfile forState(VoiceState state) {
        return switch (state) {
            case IDLE -> new CoreMotionProfile(state, "READY", 0.12, 0.025, false, false, false);
            case LISTENING -> new CoreMotionProfile(state, "LISTENING", 0.12, 0.05, true, false, false);
            case PROCESSING -> new CoreMotionProfile(state, "PROCESSING", 0.85, 0.035, false, false, false);
            case EXECUTING -> new CoreMotionProfile(state, "EXECUTING", 0.45, 0.025, false, false, false);
            case SPEAKING -> new CoreMotionProfile(state, "SPEAKING", 0.12, 0.04, false, true, false);
            case ERROR -> new CoreMotionProfile(state, "ERROR", 0.0, 0.0, false, false, true);
        };
    }
}
