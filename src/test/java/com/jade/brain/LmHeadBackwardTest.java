package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.*;
import com.jade.brain.model.*;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class LmHeadBackwardTest {
    private static final double EPSILON = 1e-6;
    private static final double TOLERANCE = 1e-8;

    private JadeLanguageModel model() {
        return new JadeLanguageModel(new BrainConfig(5, 8, 4, 2, 1, 8), 26167);
    }
    private double loss(LmHead head, Matrix hidden, int[] targets) {
        return CrossEntropyLoss.mean(head.forward(hidden).toArray(), targets, head.weights().columns());
    }
    private static void equal(Matrix first, Matrix second) {
        assertEquals(first.rows(), second.rows());
        assertEquals(first.columns(), second.columns());
        for (int i = 0; i < first.rows(); i++) assertArrayEquals(first.row(i), second.row(i));
    }

    @Test void uniformSinglePositionGradientMatchesAnalyticalValues() {
        var result = CrossEntropyLoss.meanWithGradient(new double[][] {{0, 0, 0, 0}}, new int[] {2}, 4);
        assertEquals(Math.log(4), result.loss());
        assertArrayEquals(new double[] {.25, .25, -.75, .25}, result.dLogits().row(0));
    }
    @Test void meanScalingDividesEveryPositionByTargetCount() {
        double[][] repeated = {{0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}};
        var result = CrossEntropyLoss.meanWithGradient(repeated, new int[] {2, 2, 2}, 4);
        assertEquals(Math.log(4), result.loss());
        for (double[] row : result.dLogits().toArray()) {
            assertArrayEquals(new double[] {1.0 / 12, 1.0 / 12, -.25, 1.0 / 12}, row, 1e-15);
        }
        Matrix hidden = new Matrix(new double[][] {{1, 2}, {1, 2}, {1, 2}});
        LmHead head = new LmHead(new Matrix(new double[2][4]));
        Matrix dWeights = head.backward(hidden, result.dLogits()).dWeights();
        assertArrayEquals(new double[] {.25, .25, -.75, .25}, dWeights.row(0), 1e-15);
        assertArrayEquals(new double[] {.5, .5, -1.5, .5}, dWeights.row(1), 1e-15);
    }
    @Test void gradientRowsSumToZeroWithCorrectSigns() {
        double[][] logits = {{1, -2, .5}, {-.2, .4, 1.5}, {3, 2, 1}};
        int[] targets = {1, 2, 0};
        Matrix gradients = CrossEntropyLoss.meanWithGradient(logits, targets, 3).dLogits();
        for (int t = 0; t < logits.length; t++) {
            assertEquals(0, Arrays.stream(gradients.row(t)).sum(), 1e-15);
            for (int i = 0; i < 3; i++) {
                if (i == targets[t]) assertTrue(gradients.get(t, i) <= 0);
                else assertTrue(gradients.get(t, i) >= 0);
            }
        }
    }
    @Test void extremeFiniteGradientsAndTranslationInvariance() {
        double[][] positive = {{10_000, 9999, 9998}};
        double[][] negative = {{-10_000, -10_001, -10_002}};
        equal(CrossEntropyLoss.meanWithGradient(positive, new int[] {2}, 3).dLogits(),
                CrossEntropyLoss.meanWithGradient(negative, new int[] {2}, 3).dLogits());
        var huge = CrossEntropyLoss.meanWithGradient(new double[][] {{Double.MAX_VALUE, Double.MAX_VALUE}}, new int[] {1}, 2);
        assertArrayEquals(new double[] {.5, -.5}, huge.dLogits().row(0));
        var original = CrossEntropyLoss.meanWithGradient(new double[][] {{1, 2, 3}}, new int[] {1}, 3);
        var shifted = CrossEntropyLoss.meanWithGradient(new double[][] {{1001, 1002, 1003}}, new int[] {1}, 3);
        equal(original.dLogits(), shifted.dLogits());
    }
    @Test void crossEntropyFiniteDifferencesAcrossAllRowsAndClasses() {
        double[][] logits = {{.1, -.3, 1.2, .8}, {-2, 1, .5, .3}, {2, -1, .2, -.7}};
        int[] targets = {2, 0, 3};
        Matrix analytical = CrossEntropyLoss.meanWithGradient(logits, targets, 4).dLogits();
        double maxError = 0;
        int checked = 0;
        for (int t = 0; t < 3; t++) for (int i = 0; i < 4; i++) {
            double[][] plus = new Matrix(logits).toArray(), minus = new Matrix(logits).toArray();
            plus[t][i] += EPSILON;
            minus[t][i] -= EPSILON;
            double numerical = (CrossEntropyLoss.mean(plus, targets, 4) - CrossEntropyLoss.mean(minus, targets, 4)) / (2 * EPSILON);
            maxError = Math.max(maxError, Math.abs(numerical - analytical.get(t, i)));
            assertEquals(numerical, analytical.get(t, i), TOLERANCE);
            checked++;
        }
        System.out.println("CE FINITE DIFFERENCE elements=" + checked + " maxAbsoluteError=" + maxError);
    }
    @Test void lmHeadBackwardMatchesHandCalculatedMatrixProductsAndShapes() {
        LmHead head = new LmHead(new Matrix(new double[][] {{1, 2, 3}, {4, 5, 6}}));
        Matrix hidden = new Matrix(new double[][] {{1, 2}, {3, 4}});
        Matrix upstream = new Matrix(new double[][] {{.1, .2, -.3}, {.4, -.5, .1}});
        var result = head.backward(hidden, upstream);
        assertEquals(2, result.dHidden().rows());
        assertEquals(2, result.dHidden().columns());
        assertEquals(2, result.dWeights().rows());
        assertEquals(3, result.dWeights().columns());
        assertArrayEquals(new double[] {-.4, -.4}, result.dHidden().row(0), 1e-14);
        assertArrayEquals(new double[] {-.3, -.3}, result.dHidden().row(1), 1e-14);
        assertArrayEquals(new double[] {1.3, -1.3, 0}, result.dWeights().row(0), 1e-14);
        assertArrayEquals(new double[] {1.8, -1.6, -.2}, result.dWeights().row(1), 1e-14);
    }
    @Test void actualModelLmHeadWeightFiniteDifferencesUseCopiesOnly() {
        JadeLanguageModel model = model();
        Matrix hidden = model.outputHidden(new int[] {1, 2, 3});
        LmHead head = model.lmHead();
        Matrix weightsBefore = new Matrix(head.weights().toArray());
        int[] targets = {2, 3, 4};
        var ce = CrossEntropyLoss.meanWithGradient(head.forward(hidden).toArray(), targets, 5);
        Matrix analytical = head.backward(hidden, ce.dLogits()).dWeights();
        double maxAbsolute = 0, maxRelative = 0;
        int checked = 0;
        for (int d = 0; d < 4; d++) for (int v = 0; v < 5; v++) {
            double[][] plus = head.weights().toArray(), minus = head.weights().toArray();
            plus[d][v] += EPSILON;
            minus[d][v] -= EPSILON;
            double numerical = (loss(new LmHead(new Matrix(plus)), hidden, targets)
                    - loss(new LmHead(new Matrix(minus)), hidden, targets)) / (2 * EPSILON);
            double expected = analytical.get(d, v);
            double error = Math.abs(numerical - expected);
            maxAbsolute = Math.max(maxAbsolute, error);
            maxRelative = Math.max(maxRelative, error / Math.max(1e-12, Math.max(Math.abs(numerical), Math.abs(expected))));
            assertEquals(numerical, expected, TOLERANCE);
            checked++;
        }
        equal(weightsBefore, head.weights());
        System.out.println("LM HEAD WEIGHT FINITE DIFFERENCE parameters=" + checked + " maxAbsoluteError=" + maxAbsolute
                + " maxRelativeError=" + maxRelative);
    }
    @Test void actualModelOutputHiddenFiniteDifferencesStopBeforeRmsNorm() {
        JadeLanguageModel model = model();
        Matrix hidden = model.outputHidden(new int[] {1, 2, 3});
        LmHead head = model.lmHead();
        int[] targets = {2, 3, 4};
        Matrix dLogits = CrossEntropyLoss.meanWithGradient(head.forward(hidden).toArray(), targets, 5).dLogits();
        Matrix analytical = head.backward(hidden, dLogits).dHidden();
        double maxError = 0;
        int checked = 0;
        for (int t = 0; t < 3; t++) for (int d = 0; d < 4; d++) {
            double[][] plus = hidden.toArray(), minus = hidden.toArray();
            plus[t][d] += EPSILON;
            minus[t][d] -= EPSILON;
            double numerical = (loss(head, new Matrix(plus), targets) - loss(head, new Matrix(minus), targets)) / (2 * EPSILON);
            maxError = Math.max(maxError, Math.abs(numerical - analytical.get(t, d)));
            assertEquals(numerical, analytical.get(t, d), TOLERANCE);
            checked++;
        }
        System.out.println("LM HEAD HIDDEN FINITE DIFFERENCE elements=" + checked + " maxAbsoluteError=" + maxError);
    }
    @Test void backwardIsDeterministicAndDoesNotChangeParametersInputsOrForward() {
        JadeLanguageModel model = model();
        int[] ids = {1, 2, 3};
        Matrix hidden = model.outputHidden(ids);
        Matrix weightsBefore = new Matrix(model.lmHead().weights().toArray());
        Matrix hiddenBefore = new Matrix(hidden.toArray());
        Matrix forwardBefore = new Matrix(model.forward(ids));
        Matrix dLogits = CrossEntropyLoss.meanWithGradient(forwardBefore.toArray(), new int[] {2, 3, 4}, 5).dLogits();
        Matrix upstreamBefore = new Matrix(dLogits.toArray());
        var first = model.lmHead().backward(hidden, dLogits);
        var second = model.lmHead().backward(hidden, dLogits);
        equal(first.dHidden(), second.dHidden());
        equal(first.dWeights(), second.dWeights());
        equal(weightsBefore, model.lmHead().weights());
        equal(hiddenBefore, hidden);
        equal(upstreamBefore, dLogits);
        equal(forwardBefore, new Matrix(model.forward(ids)));
        assertArrayEquals(new int[] {1, 2, 3}, ids);
    }
    @Test void lossResultIsImmutableAndDoesNotChangeCallerArrays() {
        double[][] logits = {{.5, -.2}, {1, 2}};
        int[] targets = {0, 1};
        Matrix before = new Matrix(logits);
        var result = CrossEntropyLoss.meanWithGradient(logits, targets, 2);
        equal(before, new Matrix(logits));
        assertArrayEquals(new int[] {0, 1}, targets);
        double[][] copy = result.dLogits().toArray();
        copy[0][0] = 999;
        assertNotEquals(999, result.dLogits().get(0, 0));
    }
    @Test void invalidCrossEntropyInputsReject() {
        assertThrows(IllegalArgumentException.class, () -> CrossEntropyLoss.meanWithGradient(new double[0][], new int[0], 2));
        assertThrows(IllegalArgumentException.class, () -> CrossEntropyLoss.meanWithGradient(new double[][] {{0, 0}}, new int[] {0, 1}, 2));
        assertThrows(IllegalArgumentException.class, () -> CrossEntropyLoss.meanWithGradient(new double[][] {{0, 0}, {0}}, new int[] {0, 0}, 2));
        assertThrows(IllegalArgumentException.class, () -> CrossEntropyLoss.meanWithGradient(new double[][] {{0, 0}}, new int[] {-1}, 2));
        assertThrows(IllegalArgumentException.class, () -> CrossEntropyLoss.meanWithGradient(new double[][] {{0, 0}}, new int[] {2}, 2));
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> CrossEntropyLoss.meanWithGradient(new double[][] {{bad, 0}}, new int[] {0}, 2));
        }
    }
    @Test void invalidLmHeadBackwardShapesRejectExplicitly() {
        LmHead head = model().lmHead();
        assertThrows(IllegalArgumentException.class, () -> head.backward(new Matrix(new double[2][3]), new Matrix(new double[2][5])));
        assertThrows(IllegalArgumentException.class, () -> head.backward(new Matrix(new double[2][4]), new Matrix(new double[1][5])));
        assertThrows(IllegalArgumentException.class, () -> head.backward(new Matrix(new double[2][4]), new Matrix(new double[2][4])));
    }
}
