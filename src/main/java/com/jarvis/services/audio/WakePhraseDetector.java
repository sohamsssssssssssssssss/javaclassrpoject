package com.jarvis.services.audio;

/**
 * Detects the wake phrase ("hello jarvis") in a spoken phrase, separating
 * wake detection from command parsing. Implementations decide how the phrase
 * is recognized; the default path reuses the approved speech-recognition
 * service rather than a new neural wake-word model.
 */
public interface WakePhraseDetector {

    /**
     * Returns true when the spoken phrase is a wake phrase. The passed
     * phrase is an already-transcribed string; detection is deterministic
     * and testable without audio.
     */
    boolean isWakePhrase(String spokenPhrase);
}
