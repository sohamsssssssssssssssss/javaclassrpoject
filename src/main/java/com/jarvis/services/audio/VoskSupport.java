package com.jarvis.services.audio;

import org.vosk.Model;
import org.vosk.SpeakerModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Owns the loaded Vosk native models. This is the only JARVIS class holding
 * Vosk-specific model objects; every other type works against the
 * {@code com.jarvis.services.audio} interfaces. One STT {@link Model} can
 * back several recognizers, so it is loaded once and shared.
 */
public final class VoskSupport implements AutoCloseable {

    private final Model model;
    private final SpeakerModel speakerModel; // nullable

    private VoskSupport(Model model, SpeakerModel speakerModel) {
        this.model = model;
        this.speakerModel = speakerModel;
    }

    /**
     * Loads the STT model from {@code modelDir} and, when {@code speakerModelDir}
     * is non-null, the speaker model too. Both must be unpacked model directories
     * (downloaded once, never committed to Git).
     */
    public static VoskSupport load(Path modelDir, Path speakerModelDir) throws AudioException {
        Objects.requireNonNull(modelDir, "modelDir");
        if (!Files.isDirectory(modelDir)) {
            throw new AudioException("Vosk STT model not found at " + modelDir
                    + ". Download vosk-model-small-en-us-0.15 and unpack it there (see docs/AUDIO_FEASIBILITY.md).");
        }
        try {
            Model model = new Model(modelDir.toString());
            SpeakerModel speakerModel = null;
            if (speakerModelDir != null) {
                if (!Files.isDirectory(speakerModelDir)) {
                    model.close();
                    throw new AudioException("Vosk speaker model not found at " + speakerModelDir
                            + ". Download vosk-model-spk-0.4 and unpack it there.");
                }
                speakerModel = new SpeakerModel(speakerModelDir.toString());
            }
            return new VoskSupport(model, speakerModel);
        } catch (IOException e) {
            throw new AudioException("Could not load Vosk models from " + modelDir + ": " + e.getMessage(), e);
        } catch (UnsatisfiedLinkError e) {
            throw new AudioException("Vosk native library failed to load on this platform: " + e.getMessage(), e);
        }
    }

    /** Shared STT model. */
    public Model model() {
        return model;
    }

    /** Loaded speaker model, or null when not configured. */
    public SpeakerModel speakerModel() {
        return speakerModel;
    }

    public boolean hasSpeakerModel() {
        return speakerModel != null;
    }

    @Override
    public void close() {
        if (speakerModel != null) {
            speakerModel.close();
        }
        model.close();
    }
}
