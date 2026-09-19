package com.jarvis.services.audio;

import java.util.Objects;

/** Result of matching one utterance against enrolled speaker profiles. */
public record SpeakerMatch(SpeakerIdentity identity, double similarity) {
    public SpeakerMatch {
        Objects.requireNonNull(identity, "identity");
        if (similarity < 0.0 || similarity > 1.0) {
            throw new IllegalArgumentException("similarity must be within [0,1]: " + similarity);
        }
    }

    public static SpeakerMatch unknown(double bestSimilarity) {
        return new SpeakerMatch(SpeakerIdentity.UNKNOWN, bestSimilarity);
    }
}
