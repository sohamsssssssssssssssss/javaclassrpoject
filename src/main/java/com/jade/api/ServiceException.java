package com.jade.api;

import java.util.Objects;

public final class ServiceException extends Exception {
    private final StructuredError error;

    public ServiceException(StructuredError error) {
        super(Objects.requireNonNull(error, "error").message());
        this.error = error;
    }

    public ServiceException(StructuredError error, Throwable cause) {
        super(Objects.requireNonNull(error, "error").message(), cause);
        this.error = error;
    }

    public StructuredError error() {
        return error;
    }
}
