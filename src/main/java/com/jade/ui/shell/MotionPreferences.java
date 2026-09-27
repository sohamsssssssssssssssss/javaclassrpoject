package com.jade.ui.shell;

import java.util.Objects;

/**
 * Centralized animation settings seam for the visual brain. Every motion
 * decision in the shell goes through this class: whether continuous
 * animation runs at all (full vs reduced motion) and how long transitions
 * are. No animation timing constants live in the view classes.
 */
public final class MotionPreferences {

    /** Motion comfort modes. REDUCED keeps the shell usable but still. */
    public enum Mode {
        FULL,
        REDUCED
    }

    /** Shared transition durations (milliseconds). */
    public static final long PAGE_FADE_MILLIS = 160;
    /** Core rotation tick for PROCESSING (full motion). */
    public static final long CORE_ROTATE_TICK_MILLIS = 40;
    /** Core breathing tick for IDLE (full motion). */
    public static final long CORE_BREATH_TICK_MILLIS = 120;
    /** Core pulse tick for LISTENING (full motion). */
    public static final long CORE_LISTEN_TICK_MILLIS = 90;
    /** Core waveform tick for SPEAKING (full motion). */
    public static final long CORE_SPEAK_TICK_MILLIS = 110;

    private volatile Mode mode = Mode.FULL;

    /** Applies a motion mode; REDUCED stops all continuous core motion. */
    public void setMode(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public Mode mode() {
        return mode;
    }

    /** True when continuous animation may run. */
    public boolean animationsEnabled() {
        return mode == Mode.FULL;
    }

    /**
     * Effective tick for a continuous animation: the given full-motion value
     * when animations are enabled, otherwise 0 meaning "do not run".
     */
    public long effectiveTick(long fullMotionTickMillis) {
        return animationsEnabled() ? fullMotionTickMillis : 0L;
    }

}
