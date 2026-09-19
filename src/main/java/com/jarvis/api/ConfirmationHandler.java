package com.jarvis.api;

/**
 * Seam through which the user approves or denies a risky action.
 *
 * <p>Production wires a JavaFX dialog; tests inject scripted decisions. The
 * handler is always invoked on a background worker thread, never on the
 * JavaFX application thread, and must be safe to call for every planned
 * operation of a multi-file mutation.</p>
 */
@FunctionalInterface
public interface ConfirmationHandler {
    enum Decision {
        CONFIRMED,
        DENIED
    }

    /**
     * @param pending immutable preview of the exact operation about to run;
     *                implementations must not mutate the filesystem from here.
     */
    Decision confirm(PendingConfirmation pending);
}
