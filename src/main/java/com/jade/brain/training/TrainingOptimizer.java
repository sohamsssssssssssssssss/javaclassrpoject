package com.jade.brain.training;

import com.jade.brain.math.Matrix;
import com.jade.brain.model.JadeLanguageModel;
import java.util.Map;
import java.util.Objects;

/** Two explicit optimizers sharing immutable, transactional update state. */
public enum TrainingOptimizer {
    SGD, ADAMW;

    public record State(JadeLanguageModel model, TrainingOptimizer type, long steps, AdamW.State adamw) {
        public State {
            Objects.requireNonNull(model); Objects.requireNonNull(type);
            if (steps < 0) throw new IllegalArgumentException("Negative optimizer steps");
            if (type == ADAMW) {
                Objects.requireNonNull(adamw);
                AdamW.validateState(model, adamw);
                if (steps != adamw.step()) throw new IllegalArgumentException("Optimizer timestep differs");
            } else if (adamw != null) throw new IllegalArgumentException("SGD has no moments");
        }
    }

    public State initial(JadeLanguageModel model) {
        return new State(model, this, 0, this == ADAMW ? AdamW.State.initial(model) : null);
    }

    public State step(State state, Map<String, Matrix> gradients, TrainingConfig config) {
        if (state.type() != this) throw new IllegalArgumentException("Optimizer type differs");
        if (this == SGD)
            return new State(Sgd.step(state.model(), gradients, config.learningRate()), this,
                    Math.addExact(state.steps(), 1), null);
        var clipped = Gradients.clip(gradients, config.clipThreshold());
        var update = AdamW.step(state.model(), clipped.gradients(), state.adamw(), config);
        return new State(update.model(), this, update.optimizer().step(), update.optimizer());
    }
}
