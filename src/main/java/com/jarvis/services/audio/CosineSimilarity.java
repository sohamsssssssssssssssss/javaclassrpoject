package com.jarvis.services.audio;

import java.util.Objects;

/** Pure cosine similarity over x-vectors; unit-tested without audio or Vosk. */
final class CosineSimilarity {

    private CosineSimilarity() {
    }

    /** Cosine of the angle between the vectors; 0.0 when either is null/zero/degenerate. */
    static double of(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA <= 0.0 || normB <= 0.0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, dot / (Math.sqrt(normA) * Math.sqrt(normB))));
    }

    /** L2-normalized copy of the vector (used to keep stored profiles scale-free). */
    static double[] normalized(double[] vector) {
        Objects.requireNonNull(vector, "vector");
        double norm = 0.0;
        for (double v : vector) {
            norm += v * v;
        }
        if (norm <= 0.0) {
            return vector.clone();
        }
        double scale = 1.0 / Math.sqrt(norm);
        double[] out = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = vector[i] * scale;
        }
        return out;
    }
}
