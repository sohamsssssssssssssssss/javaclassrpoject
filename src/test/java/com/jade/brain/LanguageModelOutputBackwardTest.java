package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.CrossEntropyLoss;
import com.jade.brain.math.Matrix;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.objective.LanguageModelExample;
import com.jade.brain.objective.LanguageModelObjective;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LanguageModelOutputBackwardTest {
    private static final BrainConfig CONFIG = new BrainConfig(4, 4, 2, 1, 2, 3);
    private static final int[] TOKENS = {0, 1, 2, 3};
    private static JadeLanguageModel model() { return new JadeLanguageModel(CONFIG, 26167); }
    private static void same(Matrix expected, Matrix actual) {
        assertEquals(expected.rows(), actual.rows());
        assertEquals(expected.columns(), actual.columns());
        for (int i = 0; i < expected.rows(); i++) assertArrayEquals(expected.row(i), actual.row(i));
    }
    private static double largestAbsolute(Matrix m) {
        double result = 0;
        for (int i = 0; i < m.rows(); i++) for (int j = 0; j < m.columns(); j++)
            result = Math.max(result, Math.abs(m.get(i, j)));
        return result;
    }
    private static String blockName(int index) { return "blocks." + index + ".attention.q.weight"; }

    @Test void uniformLogitsShiftedTargetsAndMeanReduction() {
        var zero = new double[][] {{0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}};
        double uniformLoss = CrossEntropyLoss.mean(zero, new int[] {1, 2, 3}, 4);
        assertEquals(Math.log(4), uniformLoss, 1e-15);
        System.out.println("OUTPUT_UNIFORM expected=" + Math.log(4) + " actual=" + uniformLoss);
        var example = LanguageModelExample.fromTokens(TOKENS);
        assertArrayEquals(new int[] {0, 1, 2}, example.inputTokenIds());
        assertArrayEquals(new int[] {1, 2, 3}, example.targetTokenIds());
        var m = model();
        var forward = m.forwardOutput(example);
        assertArrayEquals(new int[] {1, 2, 3}, forward.targets());
        assertEquals(3, forward.supervisedTokenCount());
        assertEquals(3, forward.logits().rows());
        assertEquals(4, forward.logits().columns());
        var backward = m.backwardOutput(forward);
        assertEquals(new LanguageModelObjective().loss(m, TOKENS), backward.meanLoss());
        assertEquals(3 * backward.meanLoss(), backward.totalLoss());
        assertEquals(backward.meanLoss(), CrossEntropyLoss.mean(forward.logits().toArray(),
                new int[] {1, 2, 3}, 4));
        assertNotEquals(backward.meanLoss(), CrossEntropyLoss.mean(forward.logits().toArray(),
                new int[] {0, 1, 2}, 4));
        for (int i = 0; i < forward.logits().rows(); i++)
            assertArrayEquals(forward.logits().row(i), m.forward(example.inputTokenIds())[i]);
    }

    @Test void logitsGradientMatchesFiniteDifferencesAndExtremeValuesStayFinite() {
        Matrix logits = new Matrix(new double[][] {
                {.4, -.2, 1.3, .7}, {-.8, .3, 1.1, -.1}, {2, .5, -.2, .3}});
        int[] targets = {3, 0, 1};
        var result = CrossEntropyLoss.meanWithGradient(logits.toArray(), targets, 4);
        AttentionBackwardTest.check("OUTPUT_LOGITS", logits, result.dLogits(),
                value -> CrossEntropyLoss.mean(value.toArray(), targets, 4));
        assertArrayEquals(new double[] {.4, -.2, 1.3, .7}, logits.row(0));
        double[][] extremes = {{1000, 999, -1000, -999}, {-1000, -999, 1000, 999}};
        var stable = CrossEntropyLoss.meanWithGradient(extremes, new int[] {0, 2}, 4);
        assertTrue(Double.isFinite(stable.loss()));
        for (int i = 0; i < stable.dLogits().rows(); i++)
            for (double gradient : stable.dLogits().row(i)) assertTrue(Double.isFinite(gradient));
        assertThrows(IllegalArgumentException.class,
                () -> CrossEntropyLoss.meanWithGradient(new double[][] {{Double.NaN, 0}}, new int[] {0}, 2));
        assertThrows(IllegalArgumentException.class,
                () -> CrossEntropyLoss.meanWithGradient(new double[][] {{Double.POSITIVE_INFINITY, 0}}, new int[] {0}, 2));
        assertThrows(IllegalArgumentException.class,
                () -> CrossEntropyLoss.meanWithGradient(new double[][] {{0, 0}}, new int[] {2}, 2));
        assertThrows(IllegalArgumentException.class,
                () -> CrossEntropyLoss.meanWithGradient(new double[][] {}, new int[] {}, 2));
    }

    @Test void headAndStackOutputGradientsMatchFiniteDifferences() {
        var m = model();
        var example = LanguageModelExample.fromTokens(TOKENS);
        var backward = m.backwardOutput(example);
        var objective = new LanguageModelObjective();
        Matrix head = m.lmHead().weights();
        AttentionBackwardTest.check("OUTPUT_LM_HEAD", head, backward.dHeadWeights(), weight -> {
            var p = new LinkedHashMap<>(m.parameters());
            p.put("lmHead.weight", weight);
            return objective.loss(new JadeLanguageModel(CONFIG, p), TOKENS);
        });
        Matrix stackOutput = m.transformerOutput(example.inputTokenIds());
        AttentionBackwardTest.check("OUTPUT_STACK_HIDDEN", stackOutput, backward.dStackOutput(), hidden ->
                CrossEntropyLoss.mean(m.lmHead().forward(hidden.rmsNorm(1e-6)).toArray(),
                        example.targetTokenIds(), CONFIG.vocabSize()));
    }

    @Test void nextTokenLossReachesFirstAndLastBlockNumerically() {
        var m = model();
        var backward = m.backwardOutput(LanguageModelExample.fromTokens(TOKENS));
        var objective = new LanguageModelObjective();
        for (int index : new int[] {0, 1}) {
            String name = blockName(index);
            Matrix gradient = backward.stackGradients().layers().get(index).attention().dQuery();
            assertTrue(largestAbsolute(gradient) > 1e-12, "Layer " + index + " must receive loss gradient");
            AttentionBackwardTest.check("OUTPUT_LAYER_" + index + "_WQ", m.parameters().get(name),
                    gradient, weight -> {
                        var p = new LinkedHashMap<>(m.parameters());
                        p.put(name, weight);
                        return objective.loss(new JadeLanguageModel(CONFIG, p), TOKENS);
                    });
        }
        assertTrue(largestAbsolute(backward.dStackInput()) > 0);
        assertEquals(2, backward.stackGradients().layers().size());
    }

    @Test void repeatedOutputBackwardIsDeterministicAndDoesNotChangeParameters() {
        var m = model();
        var before = new LinkedHashMap<String, double[][]>();
        for (Map.Entry<String, Matrix> entry : m.parameters().entrySet())
            before.put(entry.getKey(), entry.getValue().toArray());
        var example = LanguageModelExample.fromTokens(TOKENS);
        var forward = m.forwardOutput(example);
        var first = m.backwardOutput(forward);
        var second = m.backwardOutput(forward);
        assertEquals(first.meanLoss(), second.meanLoss());
        same(first.logits(), second.logits());
        same(first.dLogits(), second.dLogits());
        same(first.dHeadWeights(), second.dHeadWeights());
        same(first.dStackOutput(), second.dStackOutput());
        same(first.dStackInput(), second.dStackInput());
        for (int layer = 0; layer < CONFIG.numberOfLayers(); layer++) {
            var a = first.stackGradients().layers().get(layer);
            var b = second.stackGradients().layers().get(layer);
            Matrix[] firstGradients = {a.attention().dQuery(), a.attention().dKey(),
                    a.attention().dValue(), a.attention().dOutput(), a.dUp(), a.dDown()};
            Matrix[] secondGradients = {b.attention().dQuery(), b.attention().dKey(),
                    b.attention().dValue(), b.attention().dOutput(), b.dUp(), b.dDown()};
            for (int i = 0; i < firstGradients.length; i++) same(firstGradients[i], secondGradients[i]);
        }
        for (Map.Entry<String, Matrix> entry : m.parameters().entrySet())
            for (int row = 0; row < entry.getValue().rows(); row++)
                assertArrayEquals(before.get(entry.getKey())[row], entry.getValue().row(row));
        assertThrows(IllegalArgumentException.class, () -> model().backwardOutput(forward));
        assertThrows(IllegalArgumentException.class, () -> m.backwardOutput((JadeLanguageModel.OutputForward) null));
        assertThrows(IllegalArgumentException.class,
                () -> m.forwardOutput(new LanguageModelExample(new int[] {0}, new int[] {4})));
    }
}
