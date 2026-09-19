package com.jarvis.services.audio;

import org.vosk.Recognizer;

import java.io.IOException;
import java.util.Objects;

/**
 * Vosk-backed offline speech-to-text. Vosk types stay here; callers see only
 * transcript strings and {@link AudioException}. Not thread-safe by design:
 * JARVIS drives it from the single voice worker thread.
 */
public final class VoskSpeechRecognitionService implements SpeechRecognitionService {

    private static final float SAMPLE_RATE = 16_000.0f;

    private final Recognizer recognizer;

    public VoskSpeechRecognitionService(VoskSupport support) throws AudioException {
        Objects.requireNonNull(support, "support");
        try {
            this.recognizer = new Recognizer(support.model(), SAMPLE_RATE);
        } catch (IOException e) {
            throw new AudioException("Could not create Vosk recognizer: " + e.getMessage(), e);
        } catch (UnsatisfiedLinkError e) {
            throw new AudioException("Vosk native library failed to load: " + e.getMessage(), e);
        }
    }

    @Override
    public void acceptPcm(byte[] pcm, int length) throws AudioException {
        if (pcm == null || length <= 0) {
            return;
        }
        try {
            recognizer.acceptWaveForm(pcm, length);
        } catch (RuntimeException e) {
            throw new AudioException("Speech recognition failed on an audio chunk: " + e.getMessage(), e);
        }
    }

    @Override
    public String completeUtterance() throws AudioException {
        try {
            String json = recognizer.getFinalResult();
            String text = TextJson.extractText(json);
            recognizer.reset(); // next utterance starts clean, per the interface contract
            return text;
        } catch (RuntimeException e) {
            throw new AudioException("Speech recognition failed to finalize an utterance: " + e.getMessage(), e);
        }
    }

    @Override
    public String partialTranscript() {
        try {
            return TextJson.extractText(recognizer.getPartialResult());
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public void reset() {
        try {
            recognizer.reset();
        } catch (RuntimeException e) {
            // a failed reset must not crash the session; the next finalize still works
        }
    }

    @Override
    public void close() {
        recognizer.close();
    }
}
