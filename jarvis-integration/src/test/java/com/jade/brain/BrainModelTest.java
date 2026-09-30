package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.Matrix;
import com.jade.brain.model.*;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class BrainModelTest {
    private static final long SEED = 26167;
    private static final BrainConfig CONFIG = new BrainConfig(8, 32, 32, 4, 1, 64);

    @Test
    void embeddingLookupReturnsTableRowsAndRejectsInvalidIds() {
        TokenEmbedding embedding = new TokenEmbedding(8, 32, new Random(SEED));
        Matrix rows = embedding.lookup(new int[] {2, 1, 2});
        assertEquals(3, rows.rows());
        assertEquals(32, rows.columns());
        assertArrayEquals(rows.row(0), rows.row(2));
        assertFalse(java.util.Arrays.equals(rows.row(0), rows.row(1)));
        assertThrows(IllegalArgumentException.class, () -> embedding.lookup(new int[] {-1}));
        assertThrows(IllegalArgumentException.class, () -> embedding.lookup(new int[] {8}));
        assertThrows(IllegalArgumentException.class, () -> embedding.lookup(new int[0]));
    }

    @Test
    void attentionMatchesManuallyCalculatedTwoHeadSoftmax() {
        BrainConfig config = new BrainConfig(3, 4, 2, 2, 1, 4);
        // Every projection weight is .02: Q/K/V rows are [.02,.02] and [.04,.04].
        Random constant = new Random(0) { @Override public double nextGaussian() { return 1; } };
        Matrix result = new CausalSelfAttention(config, constant)
                .forward(new Matrix(new double[][] {{1, 0}, {0, 2}}));
        assertEquals(2, result.rows());
        assertEquals(2, result.columns());
        assertArrayEquals(new double[] {.0008, .0008}, result.row(0), 1e-16);
        double earlierProbability = 1 / (1 + Math.exp(.0016 - .0008));
        double expected = .04 * (earlierProbability * .02 + (1 - earlierProbability) * .04);
        assertArrayEquals(new double[] {expected, expected}, result.row(1), 1e-16);
    }

    @Test
    void attentionFutureChangesCannotAffectEarlierRepresentations() {
        CausalSelfAttention attention = new CausalSelfAttention(CONFIG, new Random(SEED));
        Matrix first = Matrix.random(4, 32, new Random(7));
        double[][] changed = first.toArray();
        java.util.Arrays.fill(changed[3], 10);
        Matrix a = attention.forward(first), b = attention.forward(new Matrix(changed));
        for (int i = 0; i < 3; i++) assertArrayEquals(a.row(i), b.row(i));
        assertFalse(java.util.Arrays.equals(a.row(3), b.row(3)), "Must not be a constant-output implementation");
    }

    @Test
    void attentionRejectsWrongWidthAndOverlongContext() {
        CausalSelfAttention attention = new CausalSelfAttention(CONFIG, new Random(SEED));
        assertThrows(IllegalArgumentException.class, () -> attention.forward(new Matrix(new double[][] {{1, 2}})));
        assertThrows(IllegalArgumentException.class, () -> attention.forward(new Matrix(new double[33][32])));
    }

    @Test
    void zeroWeightTransformerPreservesResidualExactly() {
        Random zeros = new Random(0) { @Override public double nextGaussian() { return 0; } };
        TransformerBlock block = new TransformerBlock(CONFIG, zeros);
        Matrix input = Matrix.random(3, 32, new Random(SEED));
        Matrix output = block.forward(input);
        assertEquals(3, output.rows());
        assertEquals(32, output.columns());
        for (int i = 0; i < input.rows(); i++) assertArrayEquals(input.row(i), output.row(i));
    }

    @Test
    void randomTransformerPreservesShapeAndChangesRepresentation() {
        Matrix input = Matrix.random(3, 32, new Random(SEED));
        Matrix result = new TransformerBlock(CONFIG, new Random(SEED)).forward(input);
        assertEquals(input.rows(), result.rows());
        assertEquals(input.columns(), result.columns());
        assertFalse(java.util.Arrays.equals(input.row(0), result.row(0)));
        for (double[] row : result.toArray()) for (double x : row) assertTrue(Double.isFinite(x));
    }

    @Test
    void logitsHaveExpectedShapeAndDeterministicInitialization() {
        JadeLanguageModel first = new JadeLanguageModel(CONFIG, SEED);
        double[][] a = first.forward(new int[] {1, 2, 3});
        double[][] b = new JadeLanguageModel(CONFIG, SEED).forward(new int[] {1, 2, 3});
        double[][] c = new JadeLanguageModel(CONFIG, SEED + 1).forward(new int[] {1, 2, 3});
        assertEquals(3, a.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals(8, a[i].length);
            assertArrayEquals(a[i], b[i]);
            assertArrayEquals(a[i], first.forward(new int[] {1, 2, 3})[i]);
            for (double x : a[i]) assertTrue(Double.isFinite(x));
        }
        assertFalse(java.util.Arrays.equals(a[0], c[0]));
        assertEquals(9728, first.parameterCount());
    }

    @Test
    void futureTokenAndAppendedTokensCannotChangeEarlierLogitsAcrossLayers() {
        BrainConfig twoLayers = new BrainConfig(8, 32, 32, 4, 2, 64);
        JadeLanguageModel model = new JadeLanguageModel(twoLayers, SEED);
        double[][] original = model.forward(new int[] {1, 2, 3, 4});
        double[][] futureChanged = model.forward(new int[] {1, 2, 3, 7});
        double[][] prefix = model.forward(new int[] {1, 2, 3});
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(original[i], futureChanged[i], "Future token leaked at position " + i);
            assertArrayEquals(prefix[i], original[i], "Appending a token changed the prefix");
        }
        assertFalse(java.util.Arrays.equals(original[3], futureChanged[3]));
    }

    @Test
    void positionalEmbeddingsDistinguishRepeatedTokens() {
        double[][] logits = new JadeLanguageModel(CONFIG, SEED).forward(new int[] {1, 1});
        assertFalse(java.util.Arrays.equals(logits[0], logits[1]));
    }

    @Test
    void languageModelRejectsEmptyOverlongAndInvalidSequences() {
        JadeLanguageModel model = new JadeLanguageModel(CONFIG, SEED);
        assertThrows(IllegalArgumentException.class, () -> model.forward(new int[0]));
        assertThrows(IllegalArgumentException.class, () -> model.forward(new int[33]));
        assertThrows(IllegalArgumentException.class, () -> model.forward(new int[] {1, -1}));
        assertThrows(IllegalArgumentException.class, () -> model.forward(new int[] {8}));
        assertEquals(32, model.forward(new int[32]).length);
    }
}
