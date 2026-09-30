package com.jade.api;

import java.util.Objects;

/** Informational input only: never an executable command or action plan. */
public record GeneralQuestion(String originalText) {
    public static final int MAX_LENGTH = 2048;

    public GeneralQuestion {
        Objects.requireNonNull(originalText, "originalText");
        if (originalText.isBlank() || originalText.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Question must be nonblank and at most " + MAX_LENGTH + " characters");
        }
    }
}
