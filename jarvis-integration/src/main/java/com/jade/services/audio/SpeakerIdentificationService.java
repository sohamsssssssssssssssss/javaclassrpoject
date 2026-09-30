package com.jade.services.audio;

/**
 * Speaker identification over PCM utterances. Profiles are enrolled once and
 * persisted; runtime utterances are matched against them with a cosine
 * similarity score. Identity personalizes greetings only and never grants
 * permissions.
 */
public interface SpeakerIdentificationService extends AutoCloseable {

    /** Learns a profile for {@code identity} from the given PCM utterances. */
    void enroll(SpeakerIdentity identity, byte[][] pcmUtterances) throws AudioException;

    /**
     * Matches one utterance against the enrolled profiles. Never returns a
     * known identity below {@link #threshold()}: the result is
     * {@link SpeakerIdentity#UNKNOWN} with the best observed similarity.
     */
    SpeakerMatch identify(byte[] pcmUtterance) throws AudioException;

    /** Similarity below which a match is reported as {@code UNKNOWN}. */
    double threshold();

    /** True when at least one profile exists. */
    boolean hasProfiles();

    /** Releases the engine; narrows {@link AutoCloseable#close()} to never throw. */
    @Override
    void close();
}
