package com.jade.services.audio;

/** Structured, visible failure of an audio subsystem (capture, model, synthesis). */
public class AudioException extends Exception {
    public AudioException(String message) {
        super(message);
    }

    public AudioException(String message, Throwable cause) {
        super(message, cause);
    }
}
