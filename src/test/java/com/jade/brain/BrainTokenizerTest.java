package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.model.BrainVocabulary;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.core.BrainTokenizerAdapter;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BrainTokenizerTest {
    @Test
    void textToLogitsFirstForwardPassDemo() {
        BrainVocabulary vocabulary = new BrainVocabulary(List.of("hello", "jade", "world", "what", "is", "recursion", "?"));
        BrainTokenizerAdapter tokenizer = new BrainTokenizerAdapter(vocabulary);
        BrainConfig config = new BrainConfig(vocabulary.size(), 32, 32, 4, 1, 64);
        JadeLanguageModel model = new JadeLanguageModel(config, 26167);
        int[] ids = tokenizer.encode("hello jade");
        double[][] logits = model.forward(ids);
        assertArrayEquals(new int[] {1, 2}, ids);
        assertEquals(ids.length, logits.length);
        double[][] repeated = new JadeLanguageModel(config, 26167).forward(ids);
        for (int i = 0; i < logits.length; i++) {
            assertEquals(vocabulary.size(), logits[i].length);
            assertArrayEquals(logits[i], repeated[i]);
            for (double x : logits[i]) assertTrue(Double.isFinite(x));
        }
        double[] last = logits[logits.length - 1];
        int top = 0;
        for (int i = 1; i < last.length; i++) if (last[i] > last[top]) top = i;
        assertTrue(top >= 0 && top < vocabulary.size());
        System.out.println("JADE BRAIN V0 RANDOM-WEIGHT DEMO (not learned intelligence)\nINPUT: hello jade\nTOKENS: "
                + java.util.Arrays.toString(ids) + "\nLOGIT SHAPE: " + logits.length + "x" + vocabulary.size()
                + "\nTOP TOKEN ID: " + top + "\nPARAMETERS: " + model.parameterCount());
    }

    @Test
    void fixedVocabularyRetainsLexerQuoteCaseAndPunctuationBehavior() {
        var vocabulary = new BrainVocabulary(List.of("hello", "jade", "hello jade", "jade?"));
        var adapter = new BrainTokenizerAdapter(vocabulary);
        assertArrayEquals(new int[] {1, 2}, adapter.encode("  hello\tjade\n"));
        assertArrayEquals(new int[] {3}, adapter.encode("\"hello jade\""));
        assertArrayEquals(new int[] {0, 4}, adapter.encode("Hello jade?"));
        assertEquals("<unk>", vocabulary.token(0));
        assertEquals("jade", vocabulary.token(2));
        assertThrows(IllegalArgumentException.class, () -> adapter.encode("\"hello jade"));
        assertThrows(IllegalArgumentException.class, () -> adapter.encode("hello\"jade"));
        assertThrows(IllegalArgumentException.class, () -> adapter.encode("a".repeat(8193)));
        assertArrayEquals(new int[0], adapter.encode("   "));
    }

    @Test
    void vocabularyOrderIsStableCopiedAndValidated() {
        var entries = new java.util.ArrayList<>(List.of("hello", "jade"));
        var vocabulary = new BrainVocabulary(entries);
        entries.set(0, "changed");
        assertEquals(1, vocabulary.id("hello"));
        assertEquals(0, vocabulary.id("not-in-vocabulary"));
        assertEquals(3, vocabulary.size());
        assertThrows(IllegalArgumentException.class, () -> new BrainVocabulary(List.of("a", "a")));
        assertThrows(IllegalArgumentException.class, () -> new BrainVocabulary(List.of("<unk>")));
        assertThrows(IllegalArgumentException.class, () -> new BrainVocabulary(List.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> vocabulary.token(-1));
        assertThrows(IllegalArgumentException.class, () -> vocabulary.token(3));
    }
}
