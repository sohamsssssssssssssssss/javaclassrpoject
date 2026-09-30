package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.Matrix;
import com.jade.brain.model.CausalSelfAttention;
import com.jade.brain.model.FeedForwardNetwork;
import com.jade.brain.model.TransformerBlock;
import com.jade.brain.model.TransformerStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransformerStackBackwardTest {
    private static final BrainConfig CONFIG = new BrainConfig(5, 8, 4, 2, 2, 6);
    private static List<TransformerBlock> layers() {
        Random random = new Random(26167);
        return List.of(new TransformerBlock(CONFIG, random), new TransformerBlock(CONFIG, random));
    }
    private static TransformerStack replace(List<TransformerBlock> layers, int index, TransformerBlock replacement) {
        var copy = new ArrayList<>(layers);
        copy.set(index, replacement);
        return new TransformerStack(copy);
    }
    private static TransformerBlock withQuery(TransformerBlock block, Matrix weight) {
        var a = block.attention();
        return new TransformerBlock(new CausalSelfAttention(CONFIG, weight, a.keyWeights(),
                a.valueWeights(), a.outputWeights()), block.ffn());
    }
    private static TransformerBlock withFfnUp(TransformerBlock block, Matrix weight) {
        var f = block.ffn();
        return new TransformerBlock(block.attention(), new FeedForwardNetwork(weight, f.downWeights()));
    }
    private static List<Matrix> weights(TransformerBlock block) {
        var a = block.attention();
        return List.of(a.queryWeights(), a.keyWeights(), a.valueWeights(), a.outputWeights(),
                block.ffn().upWeights(), block.ffn().downWeights());
    }
    private static void same(Matrix expected, Matrix actual) {
        assertEquals(expected.rows(), actual.rows());
        assertEquals(expected.columns(), actual.columns());
        for (int i = 0; i < expected.rows(); i++) assertArrayEquals(expected.row(i), actual.row(i));
    }
    private static double largestAbsolute(Matrix value) {
        double maximum = 0;
        for (int i = 0; i < value.rows(); i++) for (int j = 0; j < value.columns(); j++)
            maximum = Math.max(maximum, Math.abs(value.get(i, j)));
        return maximum;
    }

    @Test void completeTwoLayerStackFiniteDifferences() {
        var blocks = layers();
        var stack = new TransformerStack(blocks);
        Matrix x = AttentionBackwardTest.x(), upstream = AttentionBackwardTest.g();
        var pass = stack.forwardCached(x);
        var gradient = stack.backward(pass, upstream);
        assertEquals(2, pass.depth());
        assertEquals(2, gradient.layers().size());
        AttentionBackwardTest.check("STACK_INPUT", x, gradient.dInput(),
                input -> AttentionBackwardTest.dot(stack.forward(input), upstream));
        for (int i = 0; i < blocks.size(); i++) {
            final int layer = i;
            var block = blocks.get(i);
            var part = gradient.layers().get(i);
            AttentionBackwardTest.check("STACK_LAYER_" + i + "_WQ", block.attention().queryWeights(),
                    part.attention().dQuery(), weight -> AttentionBackwardTest.dot(
                            replace(blocks, layer, withQuery(block, weight)).forward(x), upstream));
            AttentionBackwardTest.check("STACK_LAYER_" + i + "_FFN_UP", block.ffn().upWeights(),
                    part.dUp(), weight -> AttentionBackwardTest.dot(
                            replace(blocks, layer, withFfnUp(block, weight)).forward(x), upstream));
        }
        assertTrue(largestAbsolute(gradient.layers().get(0).attention().dQuery()) > 1e-12);
        assertTrue(largestAbsolute(gradient.layers().get(0).dUp()) > 1e-12);
    }

    @Test void reverseTraversalMatchesManualLastThenFirstAndRejectsForwardOrder() {
        var blocks = layers();
        var stack = new TransformerStack(blocks);
        Matrix x = AttentionBackwardTest.x(), upstream = AttentionBackwardTest.g();
        var actual = stack.backward(stack.forwardCached(x), upstream);
        Matrix afterFirst = blocks.get(0).forward(x);
        var last = blocks.get(1).backward(afterFirst, upstream);
        var first = blocks.get(0).backward(x, last.dInput());
        same(first.dInput(), actual.dInput());
        same(first.attention().dQuery(), actual.layers().get(0).attention().dQuery());
        same(last.attention().dQuery(), actual.layers().get(1).attention().dQuery());
        var wrongFirst = blocks.get(0).backward(x, upstream);
        var wrongLast = blocks.get(1).backward(afterFirst, wrongFirst.dInput());
        double difference = 0;
        for (int i = 0; i < x.rows(); i++) for (int j = 0; j < x.columns(); j++)
            difference = Math.max(difference, Math.abs(actual.dInput().get(i, j) - wrongLast.dInput().get(i, j)));
        assertTrue(difference > 1e-10, "A forward-order backward pass must fail this fixture");
    }

    @Test void allLayerGradientsDeterministicAndParametersUnchanged() {
        var blocks = layers();
        var stack = new TransformerStack(blocks);
        Matrix x = AttentionBackwardTest.x(), upstream = AttentionBackwardTest.g();
        var before = new ArrayList<List<double[][]>>();
        for (var block : blocks) {
            var original = new ArrayList<double[][]>();
            for (Matrix weight : weights(block)) original.add(weight.toArray());
            before.add(original);
        }
        var pass = stack.forwardCached(x);
        var first = stack.backward(pass, upstream);
        var second = stack.backward(pass, upstream);
        same(pass.output(), stack.forward(x));
        same(first.dInput(), second.dInput());
        for (int layer = 0; layer < blocks.size(); layer++) {
            var a = first.layers().get(layer);
            var b = second.layers().get(layer);
            List<Matrix> gradientsA = List.of(a.attention().dQuery(), a.attention().dKey(),
                    a.attention().dValue(), a.attention().dOutput(), a.dUp(), a.dDown());
            List<Matrix> gradientsB = List.of(b.attention().dQuery(), b.attention().dKey(),
                    b.attention().dValue(), b.attention().dOutput(), b.dUp(), b.dDown());
            for (int i = 0; i < gradientsA.size(); i++) {
                same(gradientsA.get(i), gradientsB.get(i));
                Matrix original = weights(blocks.get(layer)).get(i);
                for (int row = 0; row < original.rows(); row++)
                    assertArrayEquals(before.get(layer).get(i)[row], original.row(row));
            }
        }
    }

    @Test void oneAndThreeLayersAndInvalidCaches() {
        var blocks = layers();
        Matrix x = AttentionBackwardTest.x(), upstream = AttentionBackwardTest.g();
        var one = new TransformerStack(List.of(blocks.get(0)));
        var onePass = one.forwardCached(x);
        same(blocks.get(0).forward(x), onePass.output());
        assertEquals(1, one.backward(onePass, upstream).layers().size());
        var three = new TransformerStack(List.of(blocks.get(0), blocks.get(1),
                new TransformerBlock(CONFIG, new Random(7))));
        var threePass = three.forwardCached(x);
        assertEquals(3, threePass.depth());
        assertEquals(3, three.backward(threePass, upstream).layers().size());
        var different = new TransformerStack(blocks);
        assertThrows(IllegalArgumentException.class, () -> one.backward(null, upstream));
        assertThrows(IllegalArgumentException.class, () -> different.backward(onePass, upstream));
        assertThrows(IllegalArgumentException.class, () -> one.backward(onePass, new Matrix(new double[][] {{1}})));
        assertThrows(NullPointerException.class, () -> one.forwardCached(null));
        assertThrows(IllegalArgumentException.class, () -> new TransformerStack(List.of()));
    }
}
