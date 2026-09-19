package com.jarvis.services.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure speaker-match logic: thresholding, UNKNOWN fallback and cosine
 * similarity over synthetic vectors. Uses a tiny test double instead of
 * Vosk so no audio device, model or native library is required.
 */
class SpeakerMatchLogicTest {

    /** Double that simulates x-vector extraction from PCM by hash of the payload. */
    static final class FakeSpeakerService implements SpeakerIdentificationService {
        private final double threshold;
        private final double[] sohamProfile;
        private final double[] vedProfile;
        private final java.util.function.Function<byte[], double[]> extractor;

        FakeSpeakerService(double threshold, double[] sohamProfile, double[] vedProfile,
                           java.util.function.Function<byte[], double[]> extractor) {
            this.threshold = threshold;
            this.sohamProfile = sohamProfile;
            this.vedProfile = vedProfile;
            this.extractor = extractor;
        }

        double[] lastExtracted;

        @Override
        public void enroll(SpeakerIdentity identity, byte[][] pcmUtterances) {
            throw new UnsupportedOperationException("enrollment is covered by integration spikes");
        }

        @Override
        public SpeakerMatch identify(byte[] pcmUtterance) {
            double[] vector = extractor.apply(pcmUtterance);
            lastExtracted = vector;
            double sohamScore = similarity(vector, sohamProfile);
            double vedScore = similarity(vector, vedProfile);
            if (sohamScore >= threshold && sohamScore > vedScore) {
                return new SpeakerMatch(SpeakerIdentity.SOHAM, sohamScore);
            }
            if (vedScore >= threshold && vedScore > sohamScore) {
                return new SpeakerMatch(SpeakerIdentity.VED, vedScore);
            }
            return SpeakerMatch.unknown(Math.max(0.0, Math.max(sohamScore, vedScore)));
        }

        private static double similarity(double[] a, double[] b) {
            if (a == null || b == null || a.length != b.length) {
                return 0.0;
            }
            double dot = 0, na = 0, nb = 0;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
                na += a[i] * a[i];
                nb += b[i] * b[i];
            }
            if (na <= 0 || nb <= 0) {
                return 0.0;
            }
            return dot / (Math.sqrt(na) * Math.sqrt(nb));
        }

        @Override
        public double threshold() {
            return threshold;
        }

        @Override
        public boolean hasProfiles() {
            return true;
        }

        @Override
        public void close() {
        }
    }

    private static byte[] payload(int seed) {
        return new byte[]{(byte) seed, (byte) (seed + 1), (byte) (seed + 2)};
    }

    private static double[] unit(double... values) {
        double norm = 0;
        for (double v : values) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i] / norm;
        }
        return out;
    }

    @Test
    void highSimilarityMatchesSoham() {
        FakeSpeakerService service = new FakeSpeakerService(0.60,
                unit(1, 0.1, 0), unit(0, 1, 0.1),
                bytes -> unit(1, 0.15, 0.02));
        SpeakerMatch match = service.identify(payload(1));
        assertSame(SpeakerIdentity.SOHAM, match.identity());
        assertTrue(match.similarity() >= 0.60);
    }

    @Test
    void highSimilarityMatchesVed() {
        FakeSpeakerService service = new FakeSpeakerService(0.60,
                unit(1, 0.1, 0), unit(0, 1, 0.1),
                bytes -> unit(0.02, 1, 0.15));
        SpeakerMatch match = service.identify(payload(2));
        assertSame(SpeakerIdentity.VED, match.identity());
        assertTrue(match.similarity() >= 0.60);
    }

    @Test
    void belowThresholdFallsBackToUnknown() {
        FakeSpeakerService service = new FakeSpeakerService(0.60,
                unit(1, 0, 0), unit(0, 1, 0),
                bytes -> unit(0.6, 0.6, 0)); // equal angle to both profiles (0.7071 after normalization)
        SpeakerMatch match = service.identify(payload(3));
        assertSame(SpeakerIdentity.UNKNOWN, match.identity());
        assertEquals(1.0 / Math.sqrt(2.0), match.similarity(), 0.0001);
    }

    @Test
    void tieGoesToUnknown() {
        FakeSpeakerService service = new FakeSpeakerService(0.60,
                unit(1, 0, 0), unit(1, 0, 0),
                bytes -> unit(1, 0, 0)); // identical profiles
        SpeakerMatch match = service.identify(payload(4));
        assertSame(SpeakerIdentity.UNKNOWN, match.identity());
    }

    @Test
    void zeroVectorIsUnknownWithZeroSimilarity() {
        FakeSpeakerService service = new FakeSpeakerService(0.60,
                unit(1, 0, 0), unit(0, 1, 0),
                bytes -> new double[]{0.0, 0.0, 0.0});
        SpeakerMatch match = service.identify(payload(5));
        assertSame(SpeakerIdentity.UNKNOWN, match.identity());
        assertEquals(0.0, match.similarity());
    }

    @Test
    void unknownSimilarityReportsBestObservedScore() {
        FakeSpeakerService service = new FakeSpeakerService(0.95,
                unit(1, 0, 0), unit(0, 1, 0),
                bytes -> unit(0.9, 0.43539, 0)); // ~0.90 to Soham, below the strict threshold
        SpeakerMatch match = service.identify(payload(6));
        assertSame(SpeakerIdentity.UNKNOWN, match.identity());
        assertEquals(0.9, match.similarity(), 0.01);
    }

    @Test
    void thresholdIsExposedForConfiguration() {
        FakeSpeakerService service = new FakeSpeakerService(0.75,
                unit(1, 0, 0), unit(0, 1, 0), bytes -> unit(1, 0, 0));
        assertEquals(0.75, service.threshold());
    }

    @Test
    void speakerMatchRejectsOutOfRangeSimilarity() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SpeakerMatch(SpeakerIdentity.SOHAM, 1.5));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SpeakerMatch(SpeakerIdentity.SOHAM, -0.1));
    }

    @Test
    void listenerReceivesBoundedChunks() {
        List<int[]> received = new ArrayList<>();
        AudioCaptureService.ChunkListener listener = (pcm, length) -> received.add(new int[]{length});
        listener.onChunk(new byte[100], 100);
        listener.onChunk(new byte[50], 50);
        assertEquals(2, received.size());
        assertEquals(100, received.get(0)[0]);
        assertEquals(50, received.get(1)[0]);
    }

    @Test
    void captureFormatIsVoskCompatible() {
        AudioCaptureService.AudioFormat format = AudioCaptureService.format();
        assertEquals(16_000.0f, format.sampleRate());
        assertEquals(16, format.sampleSizeBits());
        assertEquals(1, format.channels());
        assertTrue(format.signed());
        assertTrue(format.littleEndian()); // little-endian required
    }
}
