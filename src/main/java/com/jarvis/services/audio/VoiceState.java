package com.jarvis.services.audio;

/** Public states of the bounded voice session, surfaced to the UI. */
public enum VoiceState {
    IDLE,
    LISTENING,
    PROCESSING,
    EXECUTING,
    SPEAKING,
    ERROR
}
