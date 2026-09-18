package com.jarvis.core;

import com.jarvis.api.StructuredError;

final class CommandParseException extends Exception {
    private final StructuredError error;

    CommandParseException(StructuredError error) {
        super(error.message());
        this.error = error;
    }

    StructuredError error() {
        return error;
    }
}
