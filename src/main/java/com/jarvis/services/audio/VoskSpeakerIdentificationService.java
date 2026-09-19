package com.jarvis.services.audio;

import org.vosk.Recognizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Vosk x-vector speaker identification. Vosk types stay in this package;
 * callers see only {@link SpeakerMatch}. One shared {@link Recognizer} with
 * the speaker model attached extracts x-vectors; enrollment stores the
 * running mean x-vector per identity and identification is cosine similarity
 * against it with a configurable {@code UNKNOWN} threshold.
 *
 * <p>Similarity math and threshold logic are pure and unit-tested without
 * audio; this class adds only the Vosk extraction step.</p>
 */
public final class VoskSpeakerIdentificationService implements SpeakerIdentificationService {

    private static final float SAMPLE_RATE = 16_000.0f;

    private final VoskSupport support;
    private final SpeakerProfileStore store;
    private final double threshold;
    private final Recognizer recognizer;

    public VoskSpeakerIdentificationService(VoskSupport support, SpeakerProfileStore store, double threshold)
            throws AudioException {
        this.support = Objects.requireNonNull(support, "support");
        this.store = Objects.requireNonNull(store, "store");
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be within [0,1]: " + threshold);
        }
        this.threshold = threshold;
        if (!support.hasSpeakerModel()) {
            throw new AudioException("Speaker identification requires the vosk-model-spk-0.4 speaker model");
        }
        // the 0.3.38 Recognizer(Model, float, SpeakerModel) constructor does not declare IOException
        this.recognizer = new Recognizer(support.model(), SAMPLE_RATE, support.speakerModel());
    }

    @Override
    public void enroll(SpeakerIdentity identity, byte[][] pcmUtterances) throws AudioException {
        Objects.requireNonNull(identity, "identity");
        if (pcmUtterances == null || pcmUtterances.length == 0) {
            throw new AudioException("Enrollment needs at least one utterance");
        }
        List<double[]> vectors = new ArrayList<>();
        for (byte[] utterance : pcmUtterances) {
            double[] vector = extractVector(utterance);
            if (vector != null) {
                vectors.add(vector);
            }
        }
        store.mergeVectors(identity, vectors);
    }

    @Override
    public SpeakerMatch identify(byte[] pcmUtterance) throws AudioException {
        double[] vector = extractVector(pcmUtterance);
        if (vector == null) {
            return SpeakerMatch.unknown(0.0);
        }
        double bestSoham = CosineSimilarity.of(vector, store.meanVector(SpeakerIdentity.SOHAM));
        double bestVed = CosineSimilarity.of(vector, store.meanVector(SpeakerIdentity.VED));
        // A tie between profiles is genuinely ambiguous: report UNKNOWN with
        // the best observed score rather than silently preferring Soham.
        if (bestSoham >= threshold && bestSoham > bestVed) {
            return new SpeakerMatch(SpeakerIdentity.SOHAM, bestSoham);
        }
        if (bestVed >= threshold && bestVed > bestSoham) {
            return new SpeakerMatch(SpeakerIdentity.VED, bestVed);
        }
        return SpeakerMatch.unknown(Math.max(0.0, Math.max(bestSoham, bestVed)));
    }

    @Override
    public double threshold() {
        return threshold;
    }

    @Override
    public boolean hasProfiles() {
        try {
            return store.meanVector(SpeakerIdentity.SOHAM) != null
                    || store.meanVector(SpeakerIdentity.VED) != null;
        } catch (AudioException e) {
            return false;
        }
    }

    /** Extracts an x-vector via the Vosk speaker recognizer; null when the audio yields none. */
    private double[] extractVector(byte[] pcm) throws AudioException {
        if (pcm == null || pcm.length == 0) {
            return null;
        }
        try {
            recognizer.reset();
            // feed in bounded ~0.5 s chunks rather than one giant array;
            // the leading reset clears any previous utterance's state
            int chunkBytes = 16_000; // 0.5 s of 16 kHz mono 16-bit audio
            byte[] chunk = new byte[Math.min(chunkBytes, pcm.length)];
            for (int offset = 0; offset < pcm.length; offset += chunkBytes) {
                int length = Math.min(chunkBytes, pcm.length - offset);
                System.arraycopy(pcm, offset, chunk, 0, length);
                recognizer.acceptWaveForm(chunk, length);
            }
            String json = recognizer.getFinalResult();
            double[] vector = TextJson.extractArray(json, "spk");
            if (vector == null) {
                return null;
            }
            return CosineSimilarity.normalized(vector);
        } catch (RuntimeException e) {
            throw new AudioException("Speaker vector extraction failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        recognizer.close();
    }
}
