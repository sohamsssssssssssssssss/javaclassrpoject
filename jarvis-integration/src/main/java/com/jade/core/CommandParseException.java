package com.jade.core;

import com.jade.api.StructuredError;

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
