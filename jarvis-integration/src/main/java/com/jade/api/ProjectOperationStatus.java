package com.jade.api;

/**
 * Outcome of one project operation. A non-zero tool exit (for example a
 * failing Maven test) is a real {@link #BUILD_FAILED} project result, not an
 * internal JADE failure; only a process that could not be launched is a
 * structured {@link com.jade.api.ServiceException}.
 */
public enum ProjectOperationStatus {
    /** The tool ran to completion and exited 0. */
    SUCCEEDED,
    /** The tool ran to completion with a non-zero exit code. */
    BUILD_FAILED,
    /** The tool did not finish inside the bounded timeout and was stopped. */
    TIMED_OUT
}
