package com.jarvis.services.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wake-phrase matching on transcribed text; fully deterministic, no audio. */
class TranscriptWakePhraseDetectorTest {

    private final TranscriptWakePhraseDetector detector = new TranscriptWakePhraseDetector();

    @Test
    void exactPhraseWakes() {
        assertTrue(detector.isWakePhrase("hello jarvis"));
    }

    @Test
    void caseAndPunctuationInsensitive() {
        assertTrue(detector.isWakePhrase("Hello, Jarvis!"));
        assertTrue(detector.isWakePhrase("HELLO JARVIS"));
    }

    @Test
    void extraWordsAroundPhraseStillWake() {
        assertTrue(detector.isWakePhrase("well hello there jarvis"));
        assertTrue(detector.isWakePhrase("hey uh jarvis hello"));
    }

    @Test
    void knownSmallModelMishearingsWake() {
        assertTrue(detector.isWakePhrase("hello jervis"));
        assertTrue(detector.isWakePhrase("hello service"));
    }

    @Test
    void stuttersAndRepeatsWake() {
        assertTrue(detector.isWakePhrase("hello hello jarvis jarvis"));
        assertTrue(detector.isWakePhrase("hello hello hello jarvis"));
    }

    @Test
    void jarvisAloneIsNotAWakePhrase() {
        assertFalse(detector.isWakePhrase("jarvis"));
        assertFalse(detector.isWakePhrase("open calculator"));
        assertFalse(detector.isWakePhrase("system status"));
    }

    @Test
    void nullAndEmptyNeverWake() {
        assertFalse(detector.isWakePhrase(null));
        assertFalse(detector.isWakePhrase(""));
        assertFalse(detector.isWakePhrase("   "));
    }

    @Test
    void unrelatedSpeechDoesNotWake() {
        assertFalse(detector.isWakePhrase("find pdf files larger than ten megabytes"));
        assertFalse(detector.isWakePhrase("what is the weather"));
    }
}
