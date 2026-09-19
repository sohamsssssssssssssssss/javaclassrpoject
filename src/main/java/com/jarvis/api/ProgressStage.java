package com.jarvis.api;

/** Ordered stages of command execution, surfaced as progress events. */
public enum ProgressStage {
    QUEUED,
    PARSING,
    PLANNING,
    AWAITING_CONFIRMATION,
    EXECUTING,
    PERSISTING
}
