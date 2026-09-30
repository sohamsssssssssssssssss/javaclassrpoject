package com.jade.api;

import java.util.List;
import java.util.Objects;

/** Answer payload and evidence, with no executable instructions or action callbacks. */
public record AnswerResult(String answerText, AnswerStatus status,
                           List<Evidence> evidence, boolean usedWeb, String question,
                           AnswerMode mode, String provider, String model, long durationMillis) implements CommandResult {
    public AnswerResult(String text, AnswerStatus status, List<Evidence> evidence, boolean usedWeb) {
        this(text, status, evidence, usedWeb, "", usedWeb ? AnswerMode.WEB_RESEARCH : AnswerMode.KNOWLEDGE, "", "", 0);
    }
    public AnswerResult {
        Objects.requireNonNull(answerText, "answerText");
        Objects.requireNonNull(status, "status");
        if (answerText.isBlank() || answerText.length() > 12000) throw new IllegalArgumentException("Invalid answer size");
        Objects.requireNonNull(question); Objects.requireNonNull(mode); Objects.requireNonNull(provider); Objects.requireNonNull(model);
        if (question.length() > GeneralQuestion.MAX_LENGTH || provider.length() > 64 || model.length() > 128 || durationMillis < 0)
            throw new IllegalArgumentException("Invalid answer metadata");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }

    /** A provider-neutral citation/reference; empty evidence is honest when nothing was consulted. */
    public record Evidence(String title, String reference) {
        public Evidence {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(reference, "reference");
            if (title.isBlank() || reference.isBlank()) throw new IllegalArgumentException("Evidence must be nonblank");
        }
    }
}
