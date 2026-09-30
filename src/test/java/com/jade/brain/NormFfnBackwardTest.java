package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.*;
import com.jade.brain.model.*;
import org.junit.jupiter.api.Test;
import java.util.Random;
import java.util.function.ToDoubleFunction;
import static org.junit.jupiter.api.Assertions.*;

class NormFfnBackwardTest {
    private static final double EPSILON = 1e-6;
    private static final double NORM_EPSILON = 1e-6;
    private static double dot(Matrix output, Matrix upstream) {
        double sum = 0;
        for (int i = 0; i < output.rows(); i++) for (int j = 0; j < output.columns(); j++) {
            sum += output.get(i, j) * upstream.get(i, j);
        }
        return sum;
    }
    private static void equal(Matrix a, Matrix b) {
        assertEquals(a.rows(), b.rows()); assertEquals(a.columns(), b.columns());
        for (int i = 0; i < a.rows(); i++) assertArrayEquals(a.row(i), b.row(i));
    }
    private static void finiteDifference(String label, Matrix input, Matrix analytical, ToDoubleFunction<Matrix> loss) {
        double max = 0;
        int checked = 0;
        for (int i = 0; i < input.rows(); i++) for (int j = 0; j < input.columns(); j++) {
            double[][] plus = input.toArray(), minus = input.toArray();
            plus[i][j] += EPSILON; minus[i][j] -= EPSILON;
            double numerical = (loss.applyAsDouble(new Matrix(plus)) - loss.applyAsDouble(new Matrix(minus))) / (2 * EPSILON);
            max = Math.max(max, Math.abs(numerical - analytical.get(i, j)));
            assertEquals(numerical, analytical.get(i, j), 3e-8, label + " [" + i + "," + j + "]");
            checked++;
        }
        System.out.println(label + " elements=" + checked + " maxAbsoluteError=" + max);
    }
    private FeedForwardNetwork ffn() {
        return new FeedForwardNetwork(new Matrix(new double[][] {{.2, -.1, .4, .3}, {-.3, .5, .2, -.2}, {.1, .3, -.4, .6}}),
                new Matrix(new double[][] {{.3, -.2, .1}, {-.4, .2, .5}, {.1, .3, -.2}, {.2, -.1, .4}}));
    }
    private Matrix input() { return new Matrix(new double[][] {{.7, -1.2, .3}, {1.1, .2, -.6}}); }
    private Matrix upstream() { return new Matrix(new double[][] {{.3, -.7, .2}, {-.4, .6, .1}}); }
    private TransformerBlock block() { return new TransformerBlock(new BrainConfig(5, 8, 3, 1, 1, 4), new Random(26167)); }

    @Test void linearInputAndWeightGradientsMatchFiniteDifferences() {
        Matrix x = input(), w = new Matrix(new double[][] {{.2, -.3}, {1, .5}, {-.4, .7}});
        Matrix g = new Matrix(new double[][] {{.5, -.2}, {.3, .7}});
        var backward = LinearBackward.calculate(x, w, g);
        finiteDifference("LINEAR_INPUT", x, backward.dInput(), value -> dot(value.multiply(w), g));
        finiteDifference("LINEAR_WEIGHTS", w, backward.dWeights(), value -> dot(x.multiply(value), g));
    }
    @Test void exactTanhGeluDerivativeMatchesFiniteDifferencesAndZero() {
        Matrix x = new Matrix(new double[][] {{-6, -3, -1, 0, .7, 2, 6}});
        Matrix g = new Matrix(new double[][] {{1, -.3, .4, 2, -.5, .8, 1}});
        Matrix gradient = x.geluBackward(g);
        assertEquals(1, gradient.get(0, 3)); // GELU'(0)=.5; upstream is 2.
        finiteDifference("GELU", x, gradient, value -> dot(value.gelu(), g));
    }
    @Test void geluSaturatedExtremeFiniteValuesDoNotProduceNaN() {
        Matrix x = new Matrix(new double[][] {{-Double.MAX_VALUE, Double.MAX_VALUE}});
        assertArrayEquals(new double[] {0, 1}, x.geluBackward(new Matrix(new double[][] {{1, 1}})).row(0));
    }
    @Test void rmsNormGradientMatchesFiniteDifferencesIncludingEpsilon() {
        Matrix x = input(), g = upstream();
        finiteDifference("RMSNORM", x, x.rmsNormBackward(g, NORM_EPSILON), value -> dot(value.rmsNorm(NORM_EPSILON), g));
        finiteDifference("RMSNORM_EPSILON_0.2", x, x.rmsNormBackward(g, .2), value -> dot(value.rmsNorm(.2), g));
    }
    @Test void rmsNormZeroHugeAndIndependentRowsRemainStable() {
        Matrix zero = new Matrix(new double[][] {{0, 0, 0}});
        assertArrayEquals(new double[] {1000, -2000, 500},
                zero.rmsNormBackward(new Matrix(new double[][] {{1, -2, .5}}), NORM_EPSILON).row(0), 1e-10);
        Matrix huge = new Matrix(new double[][] {{1e300, -2e300, 3e300}});
        Matrix gradient = huge.rmsNormBackward(new Matrix(new double[][] {{.4, -.7, .2}}), NORM_EPSILON);
        boolean nonzero = false;
        for (double value : gradient.row(0)) { assertTrue(Double.isFinite(value)); nonzero |= value != 0; }
        assertTrue(nonzero);
        Matrix rows = input().rmsNormBackward(new Matrix(new double[][] {{0, 0, 0}, {.4, -.7, .2}}), NORM_EPSILON);
        assertArrayEquals(new double[] {0, 0, 0}, rows.row(0));
    }
    @Test void completeFfnInputAndBothParameterGradientsMatchFiniteDifferences() {
        FeedForwardNetwork network = ffn();
        Matrix x = input(), g = upstream();
        var backward = network.backward(x, g);
        finiteDifference("FFN_INPUT", x, backward.dInput(), value -> dot(network.forward(value), g));
        finiteDifference("FFN_UP", network.upWeights(), backward.dUp(),
                value -> dot(new FeedForwardNetwork(value, network.downWeights()).forward(x), g));
        finiteDifference("FFN_DOWN", network.downWeights(), backward.dDown(),
                value -> dot(new FeedForwardNetwork(network.upWeights(), value).forward(x), g));
    }
    @Test void residualGradientAddsDirectAndBranchPaths() {
        TransformerBlock block = block();
        Matrix x = input(), g = upstream();
        var branch = block.ffn().backward(x.rmsNorm(NORM_EPSILON), g);
        Matrix expected = g.add(x.rmsNormBackward(branch.dInput(), NORM_EPSILON));
        equal(expected, block.backwardFfnResidual(x, g).dAttentionResidual());
        assertNotEquals(branch.dInput().get(0, 0), expected.get(0, 0));
        Random zeros = new Random(0) { @Override public double nextGaussian() { return 0; } };
        var zeroBlock = new TransformerBlock(new BrainConfig(5, 8, 3, 1, 1, 4), zeros);
        equal(g, zeroBlock.backwardFfnResidual(x, g).dAttentionResidual());
    }
    @Test void residualNormFfnCompositionMatchesFiniteDifferencesAtAttentionBoundary() {
        TransformerBlock block = block();
        Matrix x = input(), g = upstream();
        var backward = block.backwardFfnResidual(x, g);
        finiteDifference("RESIDUAL_FFN", x, backward.dAttentionResidual(), value -> dot(block.ffnResidualForward(value), g));
    }
    @Test void finalNormHeadMeanCeCompositionMatchesFiniteDifferencesOnOriginalHidden() {
        Matrix x = input();
        LmHead head = new LmHead(new Matrix(new double[][] {{.2, -.3, .1, .4}, {-.2, .5, .3, -.1}, {.4, .1, -.5, .2}}));
        int[] targets = {1, 3};
        Matrix normalized = x.rmsNorm(NORM_EPSILON);
        var ce = CrossEntropyLoss.meanWithGradient(head.forward(normalized).toArray(), targets, 4);
        Matrix analytical = x.rmsNormBackward(head.backward(normalized, ce.dLogits()).dHidden(), NORM_EPSILON);
        finiteDifference("FINAL_NORM_HEAD_CE", x, analytical,
                value -> CrossEntropyLoss.mean(head.forward(value.rmsNorm(NORM_EPSILON)).toArray(), targets, 4));
    }
    @Test void realModelFinalNormBackwardConnectsActualLmHeadGradientWithoutParameterUpdates() {
        var model = new JadeLanguageModel(new BrainConfig(5, 8, 4, 2, 1, 8), 26167);
        int[] ids = {1, 2, 3}, targets = {2, 3, 4};
        Matrix original = model.transformerOutput(ids);
        Matrix forwardBefore = new Matrix(model.forward(ids));
        Matrix headBefore = new Matrix(model.lmHead().weights().toArray());
        var ce = CrossEntropyLoss.meanWithGradient(forwardBefore.toArray(), targets, 5);
        Matrix dHidden = model.lmHead().backward(model.outputHidden(ids), ce.dLogits()).dHidden();
        Matrix analytical = model.backwardFinalNorm(ids, dHidden);
        equal(original.rmsNormBackward(dHidden, NORM_EPSILON), analytical);
        finiteDifference("ACTUAL_FINAL_NORM_HEAD_CE", original, analytical,
                value -> CrossEntropyLoss.mean(model.lmHead().forward(value.rmsNorm(NORM_EPSILON)).toArray(), targets, 5));
        equal(headBefore, model.lmHead().weights());
        equal(forwardBefore, new Matrix(model.forward(ids)));
    }
    @Test void backwardDeterminismAndParameterInputImmutability() {
        TransformerBlock block = block();
        Matrix x = input(), g = upstream();
        Matrix upBefore = new Matrix(block.ffn().upWeights().toArray()), downBefore = new Matrix(block.ffn().downWeights().toArray());
        Matrix inputBefore = new Matrix(x.toArray()), upstreamBefore = new Matrix(g.toArray());
        Matrix forwardBefore = block.forward(x);
        var first = block.backwardFfnResidual(block.afterAttention(x), g);
        var second = block.backwardFfnResidual(block.afterAttention(x), g);
        equal(first.dAttentionResidual(), second.dAttentionResidual());
        equal(first.dUp(), second.dUp()); equal(first.dDown(), second.dDown());
        equal(upBefore, block.ffn().upWeights()); equal(downBefore, block.ffn().downWeights());
        equal(inputBefore, x); equal(upstreamBefore, g); equal(forwardBefore, block.forward(x));
        double[][] copy = first.dUp().toArray(); copy[0][0] = 999;
        assertNotEquals(999, first.dUp().get(0, 0));
    }
    @Test void malformedShapesAndInvalidNumericalInputsReject() {
        Matrix x = input(), wrong = new Matrix(new double[][] {{1}});
        assertThrows(IllegalArgumentException.class, () -> x.geluBackward(wrong));
        assertThrows(IllegalArgumentException.class, () -> x.rmsNormBackward(wrong, NORM_EPSILON));
        assertThrows(IllegalArgumentException.class, () -> x.rmsNormBackward(upstream(), 0));
        assertThrows(IllegalArgumentException.class, () -> x.rmsNormBackward(upstream(), Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> LinearBackward.calculate(x, wrong, upstream()));
        assertThrows(IllegalArgumentException.class, () -> ffn().backward(x, wrong));
        assertThrows(IllegalArgumentException.class, () -> block().backwardFfnResidual(x, wrong));
        assertThrows(IllegalArgumentException.class, () -> new FeedForwardNetwork(wrong, new Matrix(new double[2][2])));
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new Matrix(new double[][] {{bad}}).geluBackward(wrong));
            assertThrows(IllegalArgumentException.class, () -> new Matrix(new double[][] {{bad}}).rmsNormBackward(wrong, NORM_EPSILON));
        }
    }
}
