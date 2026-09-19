package com.jarvis.api;

/** Outcome of one planned file operation inside a {@link MutationReceipt}. */
public enum OperationStatus {
    APPLIED,
    FAILED
}
