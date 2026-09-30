package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.generation.*;
import com.jade.brain.model.BrainVocabulary;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.core.BrainTokenizerAdapter;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static com.jade.brain.generation.GenerationResult.StopReason.*;
import static org.junit.jupiter.api.Assertions.*;

class BrainGenerationTest {
    private final BrainVocabulary vocabulary = new BrainVocabulary(
            List.of("hello", "jade", "world", "what", "is", "recursion", "?"));

    private JadeLanguageModel model(int contextLength) {
        return new JadeLanguageModel(new BrainConfig(vocabulary.size(), contextLength, 32, 4, 1, 64), 26167);
    }

    private JadeTextGenerator generator(JadeLanguageModel model) {
        return new JadeTextGenerator(model, new BrainTokenizerAdapter(vocabulary), TokenSelector.greedy());
    }

    @Test
    void vocabularyDecodesUnknownAndEveryKnownEntryRoundTrips() {
        var adapter = new BrainTokenizerAdapter(vocabulary);
        for (int id = 0; id < vocabulary.size(); id++) {
            String token = vocabulary.token(id);
            assertEquals(id, vocabulary.id(token));
            assertEquals(token, vocabulary.decode(new int[] {id}));
            assertArrayEquals(new int[] {id}, adapter.encode(token));
        }
        assertEquals("<unk> hello jade", vocabulary.decode(new int[] {0, 1, 2}));
        assertEquals("", vocabulary.decode(new int[0]));
    }

    @Test
    void decodeRejectsInvalidIds() {
        assertThrows(IllegalArgumentException.class, () -> vocabulary.decode(new int[] {-1}));
        assertThrows(IllegalArgumentException.class, () -> vocabulary.decode(new int[] {1, 8}));
    }

    @Test
    void nextTokenLogitsAreExactlyFinalForwardRowAndFinite() {
        JadeLanguageModel model = model(32);
        int[] ids = {1, 2, 3};
        double[] next = model.nextTokenLogits(ids);
        assertEquals(vocabulary.size(), next.length);
        assertArrayEquals(model.forward(ids)[2], next);
        assertArrayEquals(next, model(32).nextTokenLogits(ids));
        for (double value : next) assertTrue(Double.isFinite(value));
        next[0] = 999;
        assertNotEquals(999, model.nextTokenLogits(ids)[0]);
    }

    @Test
    void greedyChoosesMaximumWithoutSoftmax() {
        var selector = TokenSelector.greedy();
        assertEquals(1, selector.select(new double[] {.1, .8, .2}));
        assertEquals(2, selector.select(new double[] {-8, -3, -1}));
        assertEquals(0, selector.select(new double[] {Double.MAX_VALUE, -Double.MAX_VALUE}));
    }

    @Test
    void greedyExactTiesChooseLowestId() {
        var selector = TokenSelector.greedy();
        assertEquals(0, selector.select(new double[] {1, 1, 1}));
        assertEquals(1, selector.select(new double[] {0, 2, 2}));
    }

    @Test
    void greedyRejectsEmptyAndNonFiniteLogits() {
        var selector = TokenSelector.greedy();
        assertThrows(IllegalArgumentException.class, () -> selector.select(new double[0]));
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> selector.select(new double[] {0, invalid}));
        }
    }

    @Test
    void oneTokenGenerationMatchesDirectModelArgmax() {
        JadeLanguageModel model = model(32);
        int expected = TokenSelector.greedy().select(model.nextTokenLogits(new int[] {1, 2}));
        var result = generator(model).generate(new int[] {1, 2}, 1);
        assertEquals(List.of(expected), result.generatedTokenIds());
        assertEquals(List.of(1, 2, expected), result.allTokenIds());
        assertEquals(vocabulary.token(expected), result.generatedText());
        assertEquals(MAX_NEW_TOKENS, result.stopReason());
    }

    @Test
    void generationFeedbackMatchesExpandedContextAtEveryStep() {
        JadeLanguageModel model = model(32);
        List<Integer> expectedContext = new ArrayList<>(List.of(1, 2));
        List<List<Integer>> verifiedContexts = new ArrayList<>();
        double[] originalPromptLogits = model.nextTokenLogits(new int[] {1, 2});
        TokenSelector checking = actualLogits -> {
            int[] context = expectedContext.stream().mapToInt(Integer::intValue).toArray();
            assertArrayEquals(model.nextTokenLogits(context), actualLogits,
                    "Generator must infer on the expanded prefix, not the original prompt");
            if (context.length > 2) assertFalse(Arrays.equals(originalPromptLogits, actualLogits));
            verifiedContexts.add(List.copyOf(expectedContext));
            int next = TokenSelector.greedy().select(actualLogits);
            expectedContext.add(next);
            return next;
        };
        var result = new JadeTextGenerator(model, new BrainTokenizerAdapter(vocabulary), checking).generate(new int[] {1, 2}, 8);
        assertEquals(8, verifiedContexts.size());
        for (int step = 0; step < 8; step++) {
            assertEquals(result.allTokenIds().subList(0, 2 + step), verifiedContexts.get(step));
        }
        assertEquals(expectedContext, result.allTokenIds());
        assertEquals(MAX_NEW_TOKENS, result.stopReason());
    }

    @Test
    void independentGreedyGenerationIsDeterministic() {
        var first = generator(model(32)).generate(new int[] {1, 2}, 8);
        var second = generator(model(32)).generate(new int[] {1, 2}, 8);
        assertEquals(first, second);
        assertEquals(first.allTokenIds(), second.allTokenIds());
    }

    @Test
    void promptAtContextLimitStopsWithoutInference() {
        var generator = new JadeTextGenerator(model(2), new BrainTokenizerAdapter(vocabulary), logits -> { fail("No inference expected"); return 0; });
        var result = generator.generate(new int[] {1, 2}, 8);
        assertEquals(CONTEXT_LIMIT, result.stopReason());
        assertEquals(List.of(), result.generatedTokenIds());
        assertEquals(List.of(1, 2), result.allTokenIds());
        assertEquals("", result.generatedText());
    }

    @Test
    void contextBoundaryStopsBeforeOverflowAndDoesNotSlide() {
        AtomicInteger calls = new AtomicInteger();
        var generator = new JadeTextGenerator(model(4), new BrainTokenizerAdapter(vocabulary), logits -> {
            calls.incrementAndGet();
            return TokenSelector.greedy().select(logits);
        });
        var result = generator.generate(new int[] {1, 2}, Integer.MAX_VALUE);
        assertEquals(2, calls.get());
        assertEquals(4, result.allTokenIds().size());
        assertEquals(List.of(1, 2), result.promptTokenIds());
        assertEquals(CONTEXT_LIMIT, result.stopReason());
    }

    @Test
    void oversizedEmptyInvalidPromptsAndNegativeBudgetReject() {
        var generator = generator(model(4));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(new int[] {1, 2, 3, 4, 5}, 1));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(new int[0], 1));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(new int[] {8}, 1));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(new int[] {-1}, 0));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(new int[] {1}, -1));
    }

    @Test
    void zeroBudgetAndSimultaneousLimitsHaveExplicitMaxTokenReason() {
        var generator = generator(model(3));
        var zero = generator.generate(new int[] {1, 2}, 0);
        assertEquals(List.of(), zero.generatedTokenIds());
        assertEquals(MAX_NEW_TOKENS, zero.stopReason());
        assertEquals(MAX_NEW_TOKENS, generator.generate(new int[] {1, 2}, 1).stopReason());
    }

    @Test
    void unknownTokenGenerationDoesNotGrowOrMutateVocabulary() {
        List<String> before = new ArrayList<>();
        for (int i = 0; i < vocabulary.size(); i++) before.add(vocabulary.token(i));
        int[] prompt = new BrainTokenizerAdapter(vocabulary).encode("hello absent");
        assertArrayEquals(new int[] {1, 0}, prompt);
        var result = generator(model(32)).generate(prompt, 8);
        assertEquals(8, result.generatedTokenIds().size());
        assertEquals(8, vocabulary.size());
        for (int i = 0; i < vocabulary.size(); i++) assertEquals(before.get(i), vocabulary.token(i));
        for (int id : result.generatedTokenIds()) assertTrue(id >= 0 && id < vocabulary.size());
    }

    @Test
    void resultAndCallerPromptStayImmutable() {
        int[] prompt = {1, 2};
        var result = generator(model(32)).generate(prompt, 8);
        assertArrayEquals(new int[] {1, 2}, prompt);
        prompt[0] = 0;
        assertEquals(List.of(1, 2), result.promptTokenIds());
        assertThrows(UnsupportedOperationException.class, () -> result.promptTokenIds().set(0, 0));
        assertThrows(UnsupportedOperationException.class, () -> result.generatedTokenIds().add(0));
        assertThrows(UnsupportedOperationException.class, () -> result.allTokenIds().add(0));
    }

    @Test
    void mismatchedVocabularyAndInvalidSelectorIdReject() {
        assertThrows(IllegalArgumentException.class, () -> new JadeTextGenerator(model(32),
                new BrainTokenizerAdapter(new BrainVocabulary(List.of("hello"))), TokenSelector.greedy()));
        for (int invalid : new int[] {-1, 8}) {
            var generator = new JadeTextGenerator(model(32), new BrainTokenizerAdapter(vocabulary), logits -> invalid);
            assertThrows(IllegalArgumentException.class, () -> generator.generate(new int[] {1, 2}, 1));
        }
    }

    @Test
    void firstAutoregressiveGenerationDemo() {
        String prompt = "hello jade";
        int[] ids = new BrainTokenizerAdapter(vocabulary).encode(prompt);
        AtomicInteger calls = new AtomicInteger();
        TokenSelector countedGreedy = logits -> {
            calls.incrementAndGet();
            return TokenSelector.greedy().select(logits);
        };
        var result = new JadeTextGenerator(model(32), new BrainTokenizerAdapter(vocabulary), countedGreedy).generate(ids, 8);
        assertEquals(8, calls.get());
        assertEquals(8, result.generatedTokenIds().size());
        assertEquals(10, result.allTokenIds().size());
        assertEquals(MAX_NEW_TOKENS, result.stopReason());
        for (int id : result.generatedTokenIds()) assertTrue(id >= 0 && id < vocabulary.size());
        System.out.println("JADE BRAIN AUTOREGRESSIVE DEMO\nPrompt: " + prompt
                + "\nPrompt IDs: " + result.promptTokenIds()
                + "\nGenerated IDs: " + result.generatedTokenIds()
                + "\nFinal token sequence: " + result.allTokenIds()
                + "\nGenerated text: " + result.generatedText()
                + "\nStop: " + result.stopReason() + "\nForward passes: " + calls.get()
                + "\nExternal inference: NO\nTrained: NO");
    }
}
