package com.jade.brain.model;

import com.jade.brain.math.Matrix;
import java.util.Objects;

/** Gradients only; immutable matrices, no bias because the forward projection has none. */
public record LmHeadBackwardResult(Matrix dHidden, Matrix dWeights) {
    public LmHeadBackwardResult {
        Objects.requireNonNull(dHidden, "dHidden");
        Objects.requireNonNull(dWeights, "dWeights");
    }
}
