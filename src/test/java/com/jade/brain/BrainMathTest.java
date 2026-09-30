package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.Matrix;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class BrainMathTest {
    @Test
    void matrixMultiplicationMatchesManualCalculation() {
        Matrix a = new Matrix(new double[][] {{1, 2, 3}, {4, 5, 6}});
        Matrix b = new Matrix(new double[][] {{7, 8}, {9, 10}, {11, 12}});
        Matrix product = a.multiply(b);
        assertArrayEquals(new double[] {58, 64}, product.row(0));
        assertArrayEquals(new double[] {139, 154}, product.row(1));
    }

    @Test
    void additionBiasTransposeAndCopiesHaveExplicitSemantics() {
        double[][] data = {{1, 2}, {3, 4}};
        Matrix a = new Matrix(data);
        data[0][0] = 999;
        assertArrayEquals(new double[] {4, 7}, a.add(a).addBias(new double[] {2, 3}).row(0));
        assertArrayEquals(new double[] {1, 3}, a.transpose().row(0));
        double[] row = a.row(0);
        row[0] = 999;
        double[][] copy = a.toArray();
        copy[0][0] = 999;
        assertEquals(1, a.get(0, 0));
    }

    @Test
    void softmaxRowsHaveCorrectProbabilitiesAndUnitSum() {
        Matrix result = new Matrix(new double[][] {{0, Math.log(2), Math.log(3)}, {5, 5, 5}}).softmaxRows();
        assertArrayEquals(new double[] {1.0 / 6, 2.0 / 6, 3.0 / 6}, result.row(0), 1e-14);
        for (double[] row : result.toArray()) assertEquals(1, Arrays.stream(row).sum(), 1e-14);
    }

    @Test
    void softmaxIsStableForLargeAndExtremeValues() {
        assertArrayEquals(Matrix.softmax(new double[] {0, 1, 2}),
                Matrix.softmax(new double[] {10_000, 10_001, 10_002}), 1e-14);
        assertArrayEquals(new double[] {1, 0}, Matrix.softmax(new double[] {Double.MAX_VALUE, -Double.MAX_VALUE}));
    }

    @Test
    void rmsNormalizationMatchesFormulaAndHandlesZeroAndHugeRows() {
        double epsilon = 1e-6;
        Matrix normalized = new Matrix(new double[][] {{3, 4}, {0, 0}, {1e300, -1e300}}).rmsNorm(epsilon);
        assertArrayEquals(new double[] {3 / Math.sqrt(12.5 + epsilon), 4 / Math.sqrt(12.5 + epsilon)},
                normalized.row(0), 1e-14);
        assertArrayEquals(new double[] {0, 0}, normalized.row(1));
        assertArrayEquals(new double[] {1, -1}, normalized.row(2), 1e-14);
        for (double[] row : normalized.toArray()) for (double x : row) assertTrue(Double.isFinite(x));
    }

    @Test
    void geluHasExpectedZeroAndSymmetry() {
        double[] result = new Matrix(new double[][] {{-1, 0, 1}}).gelu().row(0);
        assertEquals(0, result[1]);
        assertEquals(1, result[2] - result[0], 1e-14);
        assertEquals(0.8411919906082768, result[2], 1e-14);
    }

    @Test
    void incompatibleShapesNonFiniteValuesAndExcessiveAllocationsReject() {
        Matrix a = new Matrix(new double[][] {{1, 2}});
        assertThrows(IllegalArgumentException.class, () -> a.multiply(a));
        assertThrows(IllegalArgumentException.class, () -> a.add(a.transpose()));
        assertThrows(IllegalArgumentException.class, () -> a.addBias(new double[] {1}));
        assertThrows(IllegalArgumentException.class, () -> new Matrix(new double[][] {{1}, {2, 3}}));
        assertThrows(IllegalArgumentException.class, () -> new Matrix(new double[0][]));
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new Matrix(new double[][] {{value}}));
            assertThrows(IllegalArgumentException.class, () -> Matrix.softmax(new double[] {value}));
        }
        assertThrows(IllegalArgumentException.class, () -> Matrix.softmax(new double[0]));
        assertThrows(IllegalArgumentException.class, () -> a.rmsNorm(0));
        assertThrows(IllegalArgumentException.class, () -> a.rmsNorm(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Matrix.random(10_000, 10_000, new java.util.Random(1)));
        Matrix huge = new Matrix(new double[][] {{Double.MAX_VALUE}});
        assertThrows(IllegalArgumentException.class, () -> huge.multiply(huge));
    }

    @Test
    void configurationRejectsInvalidHeadsDimensionsAndHugeModels() {
        assertThrows(IllegalArgumentException.class, () -> new BrainConfig(4, 32, 32, 0, 1, 64));
        assertThrows(IllegalArgumentException.class, () -> new BrainConfig(4, 32, 31, 4, 1, 64));
        assertThrows(IllegalArgumentException.class, () -> new BrainConfig(0, 32, 32, 4, 1, 64));
        assertThrows(IllegalArgumentException.class, () -> new BrainConfig(4, 0, 32, 4, 1, 64));
        assertThrows(IllegalArgumentException.class, () -> new BrainConfig(4, 32, 32, 4, 0, 64));
        assertThrows(IllegalArgumentException.class, () -> new BrainConfig(4, 32, 32, 4, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new BrainConfig(65_536, 32, 4096, 4, 1, 4096));
        assertThrows(IllegalArgumentException.class, () -> new BrainConfig(Integer.MAX_VALUE, 32, 32, 4, 1, 64));
    }
}
