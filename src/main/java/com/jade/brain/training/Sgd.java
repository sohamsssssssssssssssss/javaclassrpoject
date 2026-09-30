package com.jade.brain.training;

import com.jade.brain.math.Matrix;
import com.jade.brain.model.JadeLanguageModel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Vanilla SGD over immutable model snapshots; a failed update cannot partially change a model. */
public final class Sgd {
    private Sgd() {}

    public record StepResult(JadeLanguageModel model, double lossBefore, double lossAfter,
                             double learningRate, int supervisedTokenCount,
                             int updatedParameterTensorCount, long updatedScalarCount) {}

    public static JadeLanguageModel step(JadeLanguageModel model, Map<String, Matrix> gradients,
                                         double learningRate) {
        Objects.requireNonNull(model, "model");
        if (!Double.isFinite(learningRate) || learningRate <= 0)
            throw new IllegalArgumentException("Learning rate must be finite and positive");
        Gradients.validate(model, Objects.requireNonNull(gradients, "gradients"));
        Map<String, Matrix> updated = new LinkedHashMap<>();
        for (var entry : model.parameters().entrySet()) {
            Matrix weight = entry.getValue(), gradient = gradients.get(entry.getKey());
            double[][] values = weight.toArray();
            for (int row = 0; row < weight.rows(); row++) {
                for (int column = 0; column < weight.columns(); column++) {
                    values[row][column] = weight.get(row, column)
                            - learningRate * gradient.get(row, column);
                }
            }
            // Matrix rejects non-finite results before any replacement model is returned.
            updated.put(entry.getKey(), new Matrix(values));
        }
        return new JadeLanguageModel(model.config(), updated);
    }

    /** One real mean next-token-loss step, with no hidden gradient accumulation. */
    public static StepResult trainStep(JadeLanguageModel model, int[] completeTokens,
                                       double learningRate) {
        var backward = model.backwardTokens(completeTokens);
        JadeLanguageModel next = step(model, backward.gradients(), learningRate);
        double after = next.backwardTokens(completeTokens).loss();
        long scalars = model.parameterCount();
        return new StepResult(next, backward.loss(), after, learningRate,
                backward.supervisedTokenCount(), model.parameters().size(), scalars);
    }
}
