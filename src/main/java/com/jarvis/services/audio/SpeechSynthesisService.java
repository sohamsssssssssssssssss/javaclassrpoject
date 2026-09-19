package com.jarvis.services.audio;

/**
 * Text-to-speech. The rest of JARVIS calls only this seam; platform
 * specifics (macOS {@code say}, Windows SAPI, …) live inside the
 * implementations.
 */
public interface SpeechSynthesisService extends AutoCloseable {

    /**
     * Speaks the given text audibly. Returns once synthesis output has been
     * fully handed off. Never call on the JavaFX Application Thread.
     */
    void speak(String text) throws AudioException;

    /** Cancels any in-progress speech. Idempotent. */
    void stop();

    /** Cancels speech and releases resources; narrows {@link AutoCloseable#close()} to never throw. */
    @Override
    void close();
}
