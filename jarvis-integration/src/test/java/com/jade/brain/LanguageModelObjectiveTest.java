package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.CrossEntropyLoss;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.objective.LanguageModelExample;
import com.jade.brain.objective.LanguageModelObjective;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LanguageModelObjectiveTest {
    @Test void completeSequenceShiftsByExactlyOneToken() {
        var example = LanguageModelExample.fromTokens(new int[] {10, 20, 30, 40});
        assertArrayEquals(new int[] {10, 20, 30}, example.inputTokenIds());
        assertArrayEquals(new int[] {20, 30, 40}, example.targetTokenIds());
    }
    @Test void examplesCopyBothConstructorInputsAndAccessorResults() {
        int[] input = {1, 2}, target = {2, 3};
        var example = new LanguageModelExample(input, target);
        input[0] = 99;
        target[0] = 99;
        example.inputTokenIds()[0] = 99;
        example.targetTokenIds()[0] = 99;
        assertArrayEquals(new int[] {1, 2}, example.inputTokenIds());
        assertArrayEquals(new int[] {2, 3}, example.targetTokenIds());
    }
    @Test void invalidExamplesReject() {
        assertThrows(NullPointerException.class, () -> LanguageModelExample.fromTokens(null));
        assertThrows(NullPointerException.class, () -> new LanguageModelExample(null, new int[] {1}));
        assertThrows(NullPointerException.class, () -> new LanguageModelExample(new int[] {1}, null));
        assertThrows(IllegalArgumentException.class, () -> LanguageModelExample.fromTokens(new int[0]));
        assertThrows(IllegalArgumentException.class, () -> LanguageModelExample.fromTokens(new int[] {1}));
        assertThrows(IllegalArgumentException.class, () -> LanguageModelExample.fromTokens(new int[] {1, -1}));
        assertThrows(IllegalArgumentException.class, () -> new LanguageModelExample(new int[] {1}, new int[] {2, 3}));
        assertThrows(IllegalArgumentException.class, () -> new LanguageModelExample(new int[0], new int[0]));
    }
    @Test void uniformLogitsEqualNaturalLogOfVocabularySize() {
        double actual = CrossEntropyLoss.at(new double[] {0, 0, 0, 0}, 2);
        assertEquals(Math.log(4), actual, 1e-14);
        System.out.println("UNIFORM CE expected: " + Math.log(4) + "; actual: " + actual);
    }
    @Test void confidentCorrectPredictionHasSmallPositiveLoss() {
        double loss = CrossEntropyLoss.at(new double[] {0, 0, 10}, 2);
        assertTrue(loss > 0 && loss < .001);
        assertEquals(Math.log1p(2 * Math.exp(-10)), loss, 1e-15);
    }
    @Test void confidentWrongPredictionHasLargeLoss() {
        double loss = CrossEntropyLoss.at(new double[] {10, 0, 0}, 2);
        assertEquals(10 + Math.log1p(2 * Math.exp(-10)), loss, 1e-14);
        assertTrue(loss > 10);
    }
    @Test void translatingAllLogitsDoesNotChangeLoss() {
        assertEquals(CrossEntropyLoss.at(new double[] {1, 2, 3}, 1),
                CrossEntropyLoss.at(new double[] {1001, 1002, 1003}, 1), 1e-14);
    }
    @Test void extremePositiveNegativeAndHugeUniformLogitsStayStable() {
        double expected = 2 + Math.log(1 + Math.exp(-1) + Math.exp(-2));
        assertEquals(expected, CrossEntropyLoss.at(new double[] {10_000, 9999, 9998}, 2), 1e-14);
        assertEquals(expected, CrossEntropyLoss.at(new double[] {-10_000, -10_001, -10_002}, 2), 1e-14);
        assertEquals(Math.log(3), CrossEntropyLoss.at(new double[] {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, 0), 1e-14);
        assertThrows(IllegalArgumentException.class,
                () -> CrossEntropyLoss.at(new double[] {Double.MAX_VALUE, -Double.MAX_VALUE}, 1));
    }
    @Test void sequenceLossIsManualArithmeticMean() {
        double[][] logits = {{0, 0}, {0, 2}, {2, 0}};
        double expected = (Math.log(2) + Math.log1p(Math.exp(-2)) + 2 + Math.log1p(Math.exp(-2))) / 3;
        assertEquals(expected, CrossEntropyLoss.mean(logits, new int[] {0, 1, 1}, 2), 1e-14);
    }
    @Test void meanDoesNotOverflowWhenIndividualLossesAreLargeAndFinite() {
        double[][] logits = {{0, -1e308}, {0, -1e308}, {0, -1e308}};
        assertEquals(1e308, CrossEntropyLoss.mean(logits, new int[] {1, 1, 1}, 2));
    }
    @Test void invalidTargetsAndNonFiniteLogitsReject() {
        assertThrows(IllegalArgumentException.class, () -> CrossEntropyLoss.at(new double[] {0, 1}, -1));
        assertThrows(IllegalArgumentException.class, () -> CrossEntropyLoss.at(new double[] {0, 1}, 2));
        assertThrows(IllegalArgumentException.class, () -> CrossEntropyLoss.at(new double[0], 0));
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> CrossEntropyLoss.at(new double[] {0, bad}, 0));
        }
    }
    @Test void malformedSequenceShapesAndVocabularyDimensionsReject() {
        assertThrows(IllegalArgumentException.class, () -> CrossEntropyLoss.mean(new double[0][], new int[0], 2));
        assertThrows(IllegalArgumentException.class, () -> CrossEntropyLoss.mean(new double[][] {{0, 0}}, new int[] {0, 1}, 2));
        assertThrows(IllegalArgumentException.class, () -> CrossEntropyLoss.mean(new double[][] {{0, 0}, {0}}, new int[] {0, 0}, 2));
        assertThrows(IllegalArgumentException.class, () -> CrossEntropyLoss.mean(new double[][] {null}, new int[] {0}, 2));
        assertThrows(IllegalArgumentException.class, () -> CrossEntropyLoss.mean(new double[][] {{0, 0}}, new int[] {0}, 3));
        assertThrows(IllegalArgumentException.class, () -> CrossEntropyLoss.mean(new double[][] {{0}}, new int[] {0}, 0));
    }
    @Test void actualRandomTransformerObjectiveIsFiniteDeterministicAndForwardOnly() {
        var model = new JadeLanguageModel(new BrainConfig(8, 32, 32, 4, 1, 64), 26167);
        int[] tokens = {1, 2, 3, 4};
        var example = LanguageModelExample.fromTokens(tokens);
        double[][] before = model.forward(example.inputTokenIds());
        assertEquals(example.targetTokenIds().length, before.length);
        for (double[] row : before) assertEquals(8, row.length);
        var objective = new LanguageModelObjective();
        double loss = objective.loss(model, tokens);
        assertTrue(Double.isFinite(loss) && loss >= 0);
        assertEquals(CrossEntropyLoss.mean(before, example.targetTokenIds(), 8), loss);
        assertEquals(loss, objective.loss(model, tokens));
        double[][] after = model.forward(example.inputTokenIds());
        for (int i = 0; i < before.length; i++) assertArrayEquals(before[i], after[i]);
        assertArrayEquals(new int[] {1, 2, 3, 4}, tokens);
        System.out.println("JADE RANDOM TRANSFORMER NEXT-TOKEN LOSS: " + loss + " (not learned language)");
    }
    @Test void objectiveValidatesAllIdsAndShiftedContextWithoutTruncation() {
        var model = new JadeLanguageModel(new BrainConfig(8, 2, 32, 4, 1, 64), 26167);
        var objective = new LanguageModelObjective();
        assertTrue(Double.isFinite(objective.loss(model, new int[] {1, 2, 3})));
        assertThrows(IllegalArgumentException.class, () -> objective.loss(model, new int[] {1, 2, 3, 4}));
        assertThrows(IllegalArgumentException.class, () -> objective.loss(model, new int[] {1, 2, 8}));
        assertThrows(IllegalArgumentException.class, () -> objective.loss(model, new int[] {1, -1}));
        assertThrows(IllegalArgumentException.class, () -> objective.loss(model, new int[] {1}));
    }
}
