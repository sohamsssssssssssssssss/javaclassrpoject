package com.jade.services.audio;

import java.util.Objects;
import java.util.Set;

/**
 * Deterministic wake-phrase matching on a transcribed phrase. Deliberately
 * text-based and testable without audio: the approved STT path produces the
 * phrase, this class only decides whether it is the wake phrase. Tolerates
 * the small-model's known mishearings ("jade"/"jervis"/"service") and
 * extra words around the phrase, and collapses repeated words so stuttered
 * transcriptions still wake JADE.
 */
public final class TranscriptWakePhraseDetector implements WakePhraseDetector {

    static final String WAKE_WORD = "jade";
    private static final Set<String> WAKE_WORD_VARIANTS =
            Set.of("jade", "jervis", "service"); // last two: documented small-model mishearings
    private static final Set<String> GREETING_WORDS = Set.of("hello", "hi", "hey");

    @Override
    public boolean isWakePhrase(String spokenPhrase) {
        if (spokenPhrase == null) {
            return false;
        }
        String normalized = spokenPhrase.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z ]", " ");
        String collapsed = normalized.replaceAll("\\b(\\w+)( \\1)+\\b", "$1");
        boolean heardWakeWord = false;
        boolean heardGreeting = false;
        for (String word : collapsed.trim().split("\\s+")) {
            if (WAKE_WORD_VARIANTS.contains(word)) {
                heardWakeWord = true;
            }
            if (GREETING_WORDS.contains(word)) {
                heardGreeting = true;
            }
        }
        // The wake phrase is "hello jade": both parts must be present, so a
        // stray mention of "jade" (or the old "jarvis" wake word) in ordinary
        // speech cannot wake JADE.
        return heardWakeWord && heardGreeting;
    }

    static final String GREETING_WORD = "hello";

    /** The wake phrase users are told to say. */
    public static String expectedPhrase() {
        return GREETING_WORD + " " + WAKE_WORD;
    }
}
