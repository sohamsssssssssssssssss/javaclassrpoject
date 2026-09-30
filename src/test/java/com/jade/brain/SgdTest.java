package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.generation.JadeTextGenerator;
import com.jade.brain.generation.TokenSelector;
import com.jade.brain.math.Matrix;
import com.jade.brain.model.BrainVocabulary;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.training.Sgd;
import com.jade.core.BrainTokenizerAdapter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SgdTest {
    private static final BrainConfig CONFIG = new BrainConfig(4, 4, 2, 1, 2, 3);
    private static final int[] TOKENS = {1, 2, 1, 3};
    private static final double RATE = .01;

    private static JadeLanguageModel model() { return new JadeLanguageModel(CONFIG, 26167); }
    private static Map<String, Matrix> gradients(JadeLanguageModel model, double value) {
        Map<String, Matrix> result = new LinkedHashMap<>();
        model.parameters().forEach((name, matrix) -> {
            double[][] data = matrix.toArray();
            for (double[] row : data) Arrays.fill(row, value);
            result.put(name, new Matrix(data));
        });
        return result;
    }
    private static void sameParameters(JadeLanguageModel a, JadeLanguageModel b) {
        assertEquals(a.parameters().keySet(), b.parameters().keySet());
        for (String name : a.parameters().keySet()) {
            Matrix x = a.parameters().get(name), y = b.parameters().get(name);
            assertEquals(x.rows(), y.rows()); assertEquals(x.columns(), y.columns());
            for (int row = 0; row < x.rows(); row++) assertArrayEquals(x.row(row), y.row(row));
        }
    }
    private static JadeTextGenerator generator(JadeLanguageModel model) {
        var vocab = new BrainVocabulary(List.of("hello", "jade", "brain"));
        return new JadeTextGenerator(model, new BrainTokenizerAdapter(vocab), TokenSelector.greedy());
    }

    @Test void exactArithmeticZeroGradientAndCompleteCoverage() {
        var initial = model();
        var weights = new LinkedHashMap<>(initial.parameters());
        double[][] sample = weights.get("embedding.weight").toArray();
        sample[0][0] = 2.0;
        weights.put("embedding.weight", new Matrix(sample));
        var model = new JadeLanguageModel(CONFIG, weights);
        var g = gradients(model, .5);
        var next = Sgd.step(model, g, .1);
        assertEquals(15, next.parameters().size()); assertEquals(80, next.parameterCount());
        assertEquals(1.95, next.parameters().get("embedding.weight").get(0, 0), 1e-15);
        for (String name : model.parameters().keySet()) {
            Matrix before = model.parameters().get(name), after = next.parameters().get(name);
            for (int row = 0; row < before.rows(); row++) for (int col = 0; col < before.columns(); col++)
                assertEquals(before.get(row, col) - .05, after.get(row, col), 0, name);
        }
        sameParameters(model, Sgd.step(model, gradients(model, 0), .1));
    }

    @Test void invalidRateShapeAndNonfiniteResultAreAtomic() {
        var model = model(); var snapshot = new JadeLanguageModel(CONFIG, model.parameters());
        for (double rate : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> Sgd.step(model, gradients(model, .1), rate));
        var missing = gradients(model, .1); missing.remove("lmHead.weight");
        assertThrows(IllegalArgumentException.class, () -> Sgd.step(model, missing, RATE));
        for (double[][] wrong : List.of(new double[][]{{1}}, new double[][]{{1, 2, 3}, {4, 5, 6}})) {
            var bad = gradients(model, .1); bad.put("lmHead.weight", new Matrix(wrong));
            assertThrows(IllegalArgumentException.class, () -> Sgd.step(model, bad, RATE));
        }
        var nullGradient = gradients(model, .1); nullGradient.put("lmHead.weight", null);
        assertThrows(NullPointerException.class, () -> Sgd.step(model, nullGradient, RATE));
        var overflow = gradients(model, 0); overflow.put("lmHead.weight",
                new Matrix(new double[][]{{Double.MAX_VALUE, 0, 0, 0}, {0, 0, 0, 0}}));
        assertThrows(IllegalArgumentException.class, () -> Sgd.step(model, overflow, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new Matrix(new double[][]{{Double.NaN}}));
        assertThrows(IllegalArgumentException.class,
                () -> new Matrix(new double[][]{{Double.POSITIVE_INFINITY}}));
        sameParameters(snapshot, model);
    }

    @Test void realLossDirectionDeltasAndUnusedEmbedding() {
        var model = model(); var original = new JadeLanguageModel(CONFIG, model.parameters());
        var back = model.backwardTokens(TOKENS);
        double before = back.loss();
        var negative = Sgd.step(model, back.gradients(), 1e-4);
        var reversed = new LinkedHashMap<String, Matrix>();
        back.gradients().forEach((name, g) -> {
            double[][] values = g.toArray();
            for (double[] row : values) for (int j = 0; j < row.length; j++) row[j] = -row[j];
            reversed.put(name, new Matrix(values));
        });
        var positive = Sgd.step(model, reversed, 1e-4);
        double down = negative.backwardTokens(TOKENS).loss();
        double up = positive.backwardTokens(TOKENS).loss();
        assertTrue(down < before, "Negative gradient must decrease real loss");
        assertTrue(up > before, "Positive gradient must increase real loss");
        assertTrue(up > down, "Wrong direction must lose to negative gradient");
        var result = Sgd.trainStep(model, TOKENS, RATE);
        assertEquals(before, result.lossBefore());
        assertTrue(result.lossAfter() < before);
        assertEquals(3, result.supervisedTokenCount());
        assertEquals(15, result.updatedParameterTensorCount());
        assertEquals(80, result.updatedScalarCount());
        for (String name : List.of("embedding.weight", "blocks.0.attention.q.weight",
                "blocks.1.attention.q.weight", "lmHead.weight")) {
            Matrix a = model.parameters().get(name), g = back.gradients().get(name);
            Matrix b = result.model().parameters().get(name);
            int row = name.equals("embedding.weight") ? 1 : 0;
            assertEquals(a.get(row, 0) - RATE * g.get(row, 0), b.get(row, 0), 0);
            System.out.printf("SGD_DELTA %s before=%.17g gradient=%.17g after=%.17g expected=%.17g error=%.3g%n",
                    name, a.get(row, 0), g.get(row, 0), b.get(row, 0),
                    a.get(row, 0) - RATE * g.get(row, 0),
                    Math.abs(b.get(row, 0) - (a.get(row, 0) - RATE * g.get(row, 0))));
        }
        assertArrayEquals(model.parameters().get("embedding.weight").row(0),
                result.model().parameters().get("embedding.weight").row(0));
        sameParameters(original, model);
        System.out.printf("SGD_FIRST lossBefore=%.17g lossAfter=%.17g absoluteChange=%.17g relativeChange=%.17g negativeDirection=%.17g wrongDirection=%.17g%n",
                before, result.lossAfter(), before - result.lossAfter(),
                (before - result.lossAfter()) / before, down, up);
    }

    @Test void deterministicStepAndBoundedMicroOverfit() {
        var model = model(); var twin = model();
        var a = Sgd.trainStep(model, TOKENS, RATE);
        var b = Sgd.trainStep(twin, TOKENS, RATE);
        assertEquals(a.lossBefore(), b.lossBefore()); assertEquals(a.lossAfter(), b.lossAfter());
        sameParameters(a.model(), b.model());
        var beforeGeneration = generator(model).generate(new int[]{1, 2}, 2);
        double initial = model.backwardTokens(TOKENS).loss();
        System.out.printf("SGD_MICRO step=0 loss=%.17g%n", initial);
        JadeLanguageModel current = model;
        for (int step = 1; step <= 30; step++) {
            var update = Sgd.trainStep(current, TOKENS, RATE);
            current = update.model();
            if (step == 1 || step == 5 || step == 10 || step == 20 || step == 30)
                System.out.printf("SGD_MICRO step=%d loss=%.17g%n", step, update.lossAfter());
        }
        double last = current.backwardTokens(TOKENS).loss();
        assertTrue(last < initial, "Tiny sequence must be learnable");
        var afterGeneration = generator(current).generate(new int[]{1, 2}, 2);
        assertEquals(CONFIG, current.config()); assertEquals(model.parameters().keySet(), current.parameters().keySet());
        assertEquals(model.parameterCount(), current.parameterCount());
        System.out.println("SGD_GENERATION before=" + beforeGeneration.generatedText()
                + " after=" + afterGeneration.generatedText()
                + " changed=" + !beforeGeneration.equals(afterGeneration));
    }
}
