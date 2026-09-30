package com.jade.brain.math;

import java.util.Objects;
import java.util.Random;

/** Immutable finite double matrix. No implicit broadcasting. */
public final class Matrix {
    private final double[][] values;
    private final int rows;
    private final int columns;

    public Matrix(double[][] input) {
        Objects.requireNonNull(input, "input");
        if (input.length == 0 || input[0] == null || input[0].length == 0) {
            throw new IllegalArgumentException("Matrix must be nonempty");
        }
        rows = input.length;
        columns = input[0].length;
        checkSize(rows, columns);
        values = new double[rows][columns];
        for (int i = 0; i < rows; i++) {
            if (input[i] == null || input[i].length != columns) {
                throw new IllegalArgumentException("Ragged matrix");
            }
            for (int j = 0; j < columns; j++) {
                if (!Double.isFinite(input[i][j])) {
                    throw new IllegalArgumentException("Matrix contains non-finite values");
                }
                values[i][j] = input[i][j];
            }
        }
    }

    private static void checkSize(int rows, int columns) {
        if (rows < 1 || columns < 1 || (long) rows * columns > 2_000_000) {
            throw new IllegalArgumentException("Invalid or excessive matrix size");
        }
    }

    public static Matrix random(int rows, int columns, Random random) {
        checkSize(rows, columns);
        Objects.requireNonNull(random, "random");
        double[][] data = new double[rows][columns];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < columns; j++) data[i][j] = random.nextGaussian() * 0.02;
        }
        return new Matrix(data);
    }

    public int rows() { return rows; }
    public int columns() { return columns; }
    public double get(int row, int column) { return values[row][column]; }
    public double[] row(int row) { return values[row].clone(); }

    public double[][] toArray() {
        double[][] copy = new double[rows][];
        for (int i = 0; i < rows; i++) copy[i] = row(i);
        return copy;
    }

    public Matrix multiply(Matrix other) {
        if (columns != other.rows) throw new IllegalArgumentException("Incompatible multiplication shapes");
        checkSize(rows, other.columns);
        double[][] result = new double[rows][other.columns];
        for (int i = 0; i < rows; i++) {
            for (int k = 0; k < columns; k++) {
                for (int j = 0; j < other.columns; j++) result[i][j] += values[i][k] * other.values[k][j];
            }
        }
        return new Matrix(result);
    }

    public Matrix add(Matrix other) {
        if (rows != other.rows || columns != other.columns) {
            throw new IllegalArgumentException("Incompatible addition shapes");
        }
        double[][] result = new double[rows][columns];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < columns; j++) result[i][j] = values[i][j] + other.values[i][j];
        }
        return new Matrix(result);
    }

    public Matrix addBias(double[] bias) {
        if (bias.length != columns) throw new IllegalArgumentException("Incompatible bias shape");
        double[][] result = toArray();
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < columns; j++) result[i][j] += bias[j];
        }
        return new Matrix(result);
    }

    public Matrix transpose() {
        double[][] result = new double[columns][rows];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < columns; j++) result[j][i] = values[i][j];
        }
        return new Matrix(result);
    }

    /** Max-subtracted softmax; callers pass only the unmasked causal prefix. */
    public static double[] softmax(double[] scores) {
        if (scores.length == 0) throw new IllegalArgumentException("Empty softmax");
        double max = -Double.MAX_VALUE;
        for (double score : scores) {
            if (!Double.isFinite(score)) throw new IllegalArgumentException("Non-finite softmax input");
            max = Math.max(max, score);
        }
        double[] result = new double[scores.length];
        double sum = 0;
        for (int i = 0; i < scores.length; i++) {
            result[i] = Math.exp(scores[i] - max);
            sum += result[i];
        }
        for (int i = 0; i < scores.length; i++) result[i] /= sum;
        return result;
    }

    public Matrix softmaxRows() {
        double[][] result = new double[rows][];
        for (int i = 0; i < rows; i++) result[i] = softmax(values[i]);
        return new Matrix(result);
    }

    /** Unit-scale RMSNorm, epsilon inside the square root, independent per position. */
    public Matrix rmsNorm(double epsilon) {
        if (!Double.isFinite(epsilon) || epsilon <= 0) throw new IllegalArgumentException("Invalid epsilon");
        double[][] result = new double[rows][columns];
        for (int i = 0; i < rows; i++) {
            double scale = 1;
            for (double value : values[i]) scale = Math.max(scale, Math.abs(value));
            double mean = 0;
            for (double value : values[i]) {
                double scaled = value / scale;
                mean += scaled * scaled / columns;
            }
            double denominator = Math.sqrt(mean + (epsilon / scale) / scale);
            for (int j = 0; j < columns; j++) result[i][j] = (values[i][j] / scale) / denominator;
        }
        return new Matrix(result);
    }

    /** Standard tanh approximation of GELU. */
    public Matrix gelu() {
        double[][] result = new double[rows][columns];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < columns; j++) {
                double x = values[i][j];
                result[i][j] = x * (0.5 * (1 + Math.tanh(Math.sqrt(2 / Math.PI)
                        * (x + 0.044715 * x * x * x))));
            }
        }
        return new Matrix(result);
    }

    private void requireSameShape(Matrix upstream) {
        Objects.requireNonNull(upstream, "upstream");
        if (rows != upstream.rows || columns != upstream.columns) {
            throw new IllegalArgumentException("Backward input/upstream shapes differ");
        }
    }

    /** Derivative of this class's exact tanh-approximate GELU forward equation. */
    public Matrix geluBackward(Matrix upstream) {
        requireSameShape(upstream);
        double[][] result = new double[rows][columns];
        double a = Math.sqrt(2 / Math.PI), b = 0.044715;
        for (int i = 0; i < rows; i++) for (int j = 0; j < columns; j++) {
            double x = values[i][j];
            double q = Math.tanh(a * (x + b * x * x * x));
            double derivative = 0.5 * (1 + q);
            // Saturated tanh has zero derivative; avoid 0 * overflow for extreme finite x.
            if (Math.abs(q) < 1) derivative += 0.5 * x * (1 - q * q) * a * (1 + 3 * b * x * x);
            result[i][j] = upstream.values[i][j] * derivative;
        }
        return new Matrix(result);
    }

    /** Unit-scale RMSNorm backward, independent per row; no gamma or mean subtraction. */
    public Matrix rmsNormBackward(Matrix upstream, double epsilon) {
        requireSameShape(upstream);
        Matrix normalized = rmsNorm(epsilon); // Reuse epsilon validation and the exact stable forward.
        double[][] result = new double[rows][columns];
        for (int i = 0; i < rows; i++) {
            double scale = 1, gradientScale = 1;
            for (int j = 0; j < columns; j++) {
                scale = Math.max(scale, Math.abs(values[i][j]));
                gradientScale = Math.max(gradientScale, Math.abs(upstream.values[i][j]));
            }
            double meanSquare = 0, meanDot = 0;
            for (int j = 0; j < columns; j++) {
                double scaled = values[i][j] / scale;
                meanSquare += scaled * scaled / columns;
                meanDot += (upstream.values[i][j] / gradientScale) * normalized.values[i][j] / columns;
            }
            double denominator = Math.sqrt(meanSquare + (epsilon / scale) / scale);
            for (int j = 0; j < columns; j++) {
                result[i][j] = ((upstream.values[i][j] / gradientScale)
                        - normalized.values[i][j] * meanDot) * (gradientScale / scale) / denominator;
            }
        }
        return new Matrix(result);
    }
}
