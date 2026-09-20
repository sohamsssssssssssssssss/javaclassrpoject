package com.jade.services.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wake-phrase matching on transcribed text; fully deterministic, no audio. */
class TranscriptWakePhraseDetectorTest {

    private final TranscriptWakePhraseDetector detector = new TranscriptWakePhraseDetector();

    @Test
    void exactPhraseWakes() {
        assertTrue(detector.isWakePhrase("hello jade"));
    }

    @Test
    void caseAndPunctuationInsensitive() {
        assertTrue(detector.isWakePhrase("Hello, Jade!"));
        assertTrue(detector.isWakePhrase("HELLO JADE"));
    }

    @Test
    void extraWordsAroundPhraseStillWake() {
        assertTrue(detector.isWakePhrase("well hello there jade"));
        assertTrue(detector.isWakePhrase("hey uh jade hello"));
    }

    @Test
    void knownSmallModelMishearingsWake() {
        assertTrue(detector.isWakePhrase("hello jervis"));
        assertTrue(detector.isWakePhrase("hello service"));
    }

    @Test
    void stuttersAndRepeatsWake() {
        assertTrue(detector.isWakePhrase("hello hello jade jarvis"));
        assertTrue(detector.isWakePhrase("hello hello hello jade"));
    }

    @Test
    void legacyJarvisPhraseDoesNotWake() {
        assertFalse(detector.isWakePhrase("jarvis"));
        assertFalse(detector.isWakePhrase("hello jarvis")); // no legacy wake compatibility
        assertFalse(detector.isWakePhrase("HELLO JARVIS"));
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
