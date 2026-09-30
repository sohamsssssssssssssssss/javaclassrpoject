package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.Matrix;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.model.TokenEmbedding;
import com.jade.brain.objective.LanguageModelObjective;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompleteTokenBackwardTest {
    private static final BrainConfig CONFIG = new BrainConfig(4, 4, 2, 1, 2, 3);
    private static final int[] TOKENS = {1, 2, 1, 3};
    private static JadeLanguageModel model() { return new JadeLanguageModel(CONFIG, 26167); }
    private static void same(Matrix a, Matrix b) {
        assertEquals(a.rows(), b.rows()); assertEquals(a.columns(), b.columns());
        for (int i = 0; i < a.rows(); i++) assertArrayEquals(a.row(i), b.row(i));
    }
    private static void checkEmbeddingRow(String label, JadeLanguageModel m,
                                          JadeLanguageModel.BackwardResult result, int id) {
        Matrix weights = m.parameters().get("embedding.weight");
        Matrix value = new Matrix(new double[][] {weights.row(id)});
        Matrix gradient = new Matrix(new double[][] {result.dTokenEmbedding().row(id)});
        AttentionBackwardTest.check(label, value, gradient, candidate -> {
            double[][] changed = weights.toArray();
            changed[id] = candidate.row(0);
            var parameters = new LinkedHashMap<>(m.parameters());
            parameters.put("embedding.weight", new Matrix(changed));
            return new LanguageModelObjective().loss(new JadeLanguageModel(CONFIG, parameters), TOKENS);
        });
    }
    private static void checkParameter(String label, JadeLanguageModel m,
                                       JadeLanguageModel.BackwardResult result, String name) {
        AttentionBackwardTest.check(label, m.parameters().get(name), result.gradients().get(name), candidate -> {
            var parameters = new LinkedHashMap<>(m.parameters());
            parameters.put(name, candidate);
            return new LanguageModelObjective().loss(new JadeLanguageModel(CONFIG, parameters), TOKENS);
        });
    }

    @Test void repeatedLookupAccumulationUnusedRowsAndEmbeddingOnlyFiniteDifferences() {
        Matrix table = new Matrix(new double[][] {
                {.1, .2, .3}, {.4, .5, .6}, {.7, .8, .9}, {1, 1.1, 1.2},
                {1.3, 1.4, 1.5}, {1.6, 1.7, 1.8}});
        var embedding = new TokenEmbedding(table);
        int[] ids = {2, 5, 2, 2};
        Matrix upstream = new Matrix(new double[][] {
                {1, 2, 3}, {4, 5, 6}, {7, 8, 9}, {10, 11, 12}});
        int[] originalIds = ids.clone();
        Matrix originalUpstream = new Matrix(upstream.toArray());
        Matrix gradient = embedding.backward(ids, upstream);
        assertArrayEquals(new double[] {18, 21, 24}, gradient.row(2));
        assertArrayEquals(new double[] {4, 5, 6}, gradient.row(5));
        assertArrayEquals(new double[] {0, 0, 0}, gradient.row(4));
        assertEquals(6, gradient.rows()); assertEquals(3, gradient.columns());
        AttentionBackwardTest.check("EMBEDDING_ONLY", table, gradient,
                candidate -> AttentionBackwardTest.dot(new TokenEmbedding(candidate).lookup(ids), upstream));
        assertArrayEquals(originalIds, ids);
        same(originalUpstream, upstream);
        same(table, embedding.weights());
    }

    @Test void invalidIdsShapesAndNonfiniteUpstreamReject() {
        var embedding = new TokenEmbedding(new Matrix(new double[6][3]));
        Matrix one = new Matrix(new double[][] {{1, 2, 3}});
        assertThrows(IllegalArgumentException.class, () -> embedding.backward(new int[] {-1}, one));
        assertThrows(IllegalArgumentException.class, () -> embedding.backward(new int[] {6}, one));
        assertThrows(IllegalArgumentException.class,
                () -> embedding.backward(new int[] {1}, new Matrix(new double[][] {{1, 2}})));
        assertThrows(IllegalArgumentException.class,
                () -> embedding.backward(new int[] {1, 2}, one));
        assertThrows(IllegalArgumentException.class,
                () -> new Matrix(new double[][] {{Double.NaN, 0, 0}}));
        assertThrows(IllegalArgumentException.class,
                () -> new Matrix(new double[][] {{Double.POSITIVE_INFINITY, 0, 0}}));
        assertThrows(IllegalArgumentException.class, () -> model().backwardTokens(new int[] {1, 2, 1, 4}));
        assertThrows(IllegalArgumentException.class, () -> model().backwardTokens(new int[] {1, 2, 1, 3, 2, 1}));
    }

    @Test void completeLossEmbeddingRowsAndCrossLayerParametersMatchFiniteDifferences() {
        var m = model();
        var result = m.backwardTokens(TOKENS);
        assertEquals(3, result.supervisedTokenCount());
        assertEquals(3, result.logits().rows()); assertEquals(4, result.logits().columns());
        assertEquals(new LanguageModelObjective().loss(m, TOKENS), result.loss());
        assertEquals(4, result.dTokenEmbedding().rows()); assertEquals(2, result.dTokenEmbedding().columns());
        assertEquals(4, result.dPositionEmbedding().rows()); assertEquals(2, result.dPositionEmbedding().columns());
        assertEquals(3, result.dEmbeddedInput().rows()); assertEquals(2, result.dEmbeddedInput().columns());
        assertArrayEquals(new double[] {0, 0}, result.dTokenEmbedding().row(0)); // unused
        assertArrayEquals(new double[] {0, 0}, result.dTokenEmbedding().row(3)); // target only
        checkEmbeddingRow("EMBEDDING_USED_ONCE", m, result, 2);
        checkEmbeddingRow("EMBEDDING_REPEATED", m, result, 1);
        checkEmbeddingRow("EMBEDDING_UNUSED", m, result, 0);
        checkParameter("COMPLETE_FIRST_BLOCK_WQ", m, result, "blocks.0.attention.q.weight");
        checkParameter("COMPLETE_LAST_BLOCK_WQ", m, result, "blocks.1.attention.q.weight");
        checkParameter("COMPLETE_LM_HEAD", m, result, "lmHead.weight");
    }

    @Test void futureTokenEmbeddingCannotChangeEarlierLogits() {
        var m = new JadeLanguageModel(new BrainConfig(5, 4, 2, 1, 2, 3), 26167);
        int[] ids = {0, 1, 2};
        double[][] baseline = m.forward(ids);
        var parameters = new LinkedHashMap<>(m.parameters());
        double[][] changed = parameters.get("embedding.weight").toArray();
        changed[2][0] += .7;
        parameters.put("embedding.weight", new Matrix(changed));
        var perturbed = new JadeLanguageModel(m.config(), parameters);
        double[][] logits = perturbed.forward(ids);
        assertArrayEquals(baseline[0], logits[0]);
        assertArrayEquals(baseline[1], logits[1]);
        assertTrue(Math.abs(baseline[2][0] - logits[2][0]) > 1e-9);
    }

    @Test void everyTrainableParameterHasFiniteShapeMatchedImmutableDeterministicGradient() {
        var m = model();
        var original = new LinkedHashMap<String, double[][]>();
        for (Map.Entry<String, Matrix> entry : m.parameters().entrySet())
            original.put(entry.getKey(), entry.getValue().toArray());
        double[][] logitsBefore = m.forward(new int[] {1, 2, 1});
        var first = m.backwardTokens(TOKENS);
        var second = m.backwardTokens(TOKENS);
        assertEquals(first.loss(), second.loss());
        assertEquals(first.supervisedTokenCount(), second.supervisedTokenCount());
        same(first.logits(), second.logits());
        same(first.dEmbeddedInput(), second.dEmbeddedInput());
        assertEquals(m.parameters().keySet(), first.gradients().keySet());
        assertEquals(first.gradients().keySet(), second.gradients().keySet());
        assertEquals(2, first.stackGradients().layers().size());
        int coveredTensors = 0, parameterScalars = 0, gradientScalars = 0;
        for (Map.Entry<String, Matrix> entry : m.parameters().entrySet()) {
            Matrix parameter = entry.getValue(), gradient = first.gradients().get(entry.getKey());
            assertEquals(parameter.rows(), gradient.rows());
            assertEquals(parameter.columns(), gradient.columns());
            same(gradient, second.gradients().get(entry.getKey()));
            for (int row = 0; row < gradient.rows(); row++) {
                assertArrayEquals(original.get(entry.getKey())[row], parameter.row(row));
                for (double value : gradient.row(row)) assertTrue(Double.isFinite(value));
            }
            coveredTensors++;
            parameterScalars += parameter.rows() * parameter.columns();
            gradientScalars += gradient.rows() * gradient.columns();
        }
        for (int i = 0; i < logitsBefore.length; i++) assertArrayEquals(logitsBefore[i], m.forward(new int[] {1, 2, 1})[i]);
        assertEquals(15, coveredTensors);
        assertEquals(m.parameterCount(), parameterScalars);
        assertEquals(parameterScalars, gradientScalars);
        System.out.println("COMPLETE_COVERAGE tensors=" + coveredTensors + "/" + m.parameters().size()
                + " scalars=" + gradientScalars + "/" + parameterScalars);
    }
}
