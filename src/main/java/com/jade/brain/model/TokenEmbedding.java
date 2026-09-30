package com.jade.brain.model;

import com.jade.brain.math.Matrix;
import java.util.Random;

public final class TokenEmbedding {
    private final Matrix table;

    public TokenEmbedding(int vocabularySize, int dimension, Random random) {
        table = Matrix.random(vocabularySize, dimension, random);
    }
    public TokenEmbedding(Matrix table) { this.table = java.util.Objects.requireNonNull(table); }
    public Matrix weights() { return table; }
    public Matrix backward(int[] tokenIds, Matrix upstream) {
        lookup(tokenIds); // Reuse ID/sequence validation.
        if (upstream.rows() != tokenIds.length || upstream.columns() != table.columns())
            throw new IllegalArgumentException("Invalid embedding gradient shape");
        double[][] gradient = new double[table.rows()][table.columns()];
        for (int i = 0; i < tokenIds.length; i++) for (int j = 0; j < table.columns(); j++)
            gradient[tokenIds[i]][j] += upstream.get(i, j);
        return new Matrix(gradient);
    }

    public Matrix lookup(int[] tokenIds) {
        if (tokenIds.length == 0) throw new IllegalArgumentException("Empty token sequence");
        if ((long) tokenIds.length * table.columns() > 2_000_000) {
            throw new IllegalArgumentException("Embedding allocation limit exceeded");
        }
        double[][] result = new double[tokenIds.length][];
        for (int i = 0; i < tokenIds.length; i++) {
            int id = tokenIds[i];
            if (id < 0 || id >= table.rows()) throw new IllegalArgumentException("Invalid token ID: " + id);
            result[i] = table.row(id);
        }
        return new Matrix(result);
    }
}
