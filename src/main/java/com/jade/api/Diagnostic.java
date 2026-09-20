package com.jade.api;

import java.util.Objects;
import java.util.Optional;

/**
 * One structured, bounded build/test diagnostic extracted from Maven or
 * Surefire evidence. Diagnostics are heuristic and conservative: output that
 * does not match a known pattern stays UNKNOWN instead of being interpreted.
 */
public record Diagnostic(
        Kind kind,
        Source source,
        Optional<String> testClass,
        Optional<String> testMethod,
        Optional<String> location,
        Optional<String> message,
        Optional<String> detail) {

    /** Maximum retained detail length per diagnostic. */
    public static final int DETAIL_LIMIT = 500;
    /** Maximum retained message length per diagnostic. */
    public static final int MESSAGE_LIMIT = 200;

    public Diagnostic {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        testClass = Objects.requireNonNull(testClass, "testClass");
        testMethod = Objects.requireNonNull(testMethod, "testMethod");
        location = Objects.requireNonNull(location, "location");
        message = Objects.requireNonNull(message, "message");
        detail = Objects.requireNonNull(detail, "detail");
    }

    /** The bounded diagnostic categories JADE understands. */
    public enum Kind {
        TEST_FAILURE,
        TEST_ERROR,
        COMPILATION_ERROR,
        BUILD_ERROR,
        UNKNOWN
    }

    /** Where the diagnostic was extracted from. */
    public enum Source {
        SUREFIRE_REPORT,
        MAVEN_OUTPUT
    }
}
