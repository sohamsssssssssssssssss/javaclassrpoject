package com.jade.brain.objective;

import com.jade.brain.math.CrossEntropyLoss;
import com.jade.brain.model.JadeLanguageModel;
import java.util.Objects;

/** Forward-only objective. Does not calculate gradients or change parameters. */
public final class LanguageModelObjective {
    public double loss(JadeLanguageModel model, int[] completeTokenSequence) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(completeTokenSequence, "completeTokenSequence");
        if (completeTokenSequence.length < 2 || completeTokenSequence.length - 1 > model.config().contextLength()) {
            throw new IllegalArgumentException("Shifted input must fit the nonempty context window");
        }
        for (int id : completeTokenSequence) {
            if (id < 0 || id >= model.config().vocabSize()) throw new IllegalArgumentException("Invalid model token ID");
        }
        LanguageModelExample example = LanguageModelExample.fromTokens(completeTokenSequence);
        return CrossEntropyLoss.mean(model.forward(example.inputTokenIds()), example.targetTokenIds(),
                model.config().vocabSize());
    }
}
