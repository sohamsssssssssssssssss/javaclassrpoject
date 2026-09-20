package com.jade.api;

import java.util.Objects;
import java.util.Optional;

public record StructuredError(ErrorCode code, String message, Optional<String> detail) {
    public StructuredError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        detail = Objects.requireNonNull(detail, "detail");
    }
}
