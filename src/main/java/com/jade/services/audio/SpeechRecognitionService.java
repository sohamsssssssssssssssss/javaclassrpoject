package com.jade.services.audio;

/**
 * Offline speech-to-text. Consumes bounded PCM chunks in the capture format
 * (16 kHz/16-bit/mono/LE) and produces transcript strings. Implementations
 * wrap external engines (Vosk); callers only ever see plain text.
 */
public interface SpeechRecognitionService extends AutoCloseable {

    /** Feeds one PCM chunk. Non-blocking for short chunks (≤1 s of audio). */
    void acceptPcm(byte[] pcm, int length) throws AudioException;

    /**
     * Ends the current utterance and returns the final transcript, or an
     * empty string when nothing was recognized. Resets internal state so the
     * next utterance starts clean.
     */
    String completeUtterance() throws AudioException;

    /** Best partial transcript so far; never blocks. Empty when nothing heard. */
    String partialTranscript();

    /** Discards everything heard so far; the next utterance starts clean. */
    void reset();

    /** Releases the engine; narrows {@link AutoCloseable#close()} to never throw. */
    @Override
    void close();
}
