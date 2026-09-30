package com.jade.api;

import java.util.Objects;

public record AnswerRequest(GeneralQuestion question) {
    public AnswerRequest {
        Objects.requireNonNull(question, "question");
    }
}
