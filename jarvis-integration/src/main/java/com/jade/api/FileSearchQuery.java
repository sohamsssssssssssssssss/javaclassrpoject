package com.jade.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Bounded filename-search query.
 *
 * <p>Sprint 2 adds upper size bounds and last-modified date windows on top of
 * the sprint 1 fields. The four-argument constructor is retained so existing
 * callers and tests keep compiling unchanged.</p>
 */
public record FileSearchQuery(
        Set<String> extensions,
        OptionalLong minimumSizeBytes,
        OptionalLong maximumSizeBytes,
        Optional<Instant> modifiedAfter,
        Optional<Instant> modifiedBefore,
        int maxResults,
        int scanLimit) {
    public FileSearchQuery {
        extensions = Set.copyOf(Objects.requireNonNull(extensions, "extensions"));
        minimumSizeBytes = Objects.requireNonNull(minimumSizeBytes, "minimumSizeBytes");
        maximumSizeBytes = Objects.requireNonNull(maximumSizeBytes, "maximumSizeBytes");
        modifiedAfter = Objects.requireNonNull(modifiedAfter, "modifiedAfter");
        modifiedBefore = Objects.requireNonNull(modifiedBefore, "modifiedBefore");
        if (extensions.isEmpty() || maxResults < 1 || scanLimit < 1) {
            throw new IllegalArgumentException("extensions and positive limits are required");
        }
        if (minimumSizeBytes.isPresent() && maximumSizeBytes.isPresent()
                && maximumSizeBytes.getAsLong() < minimumSizeBytes.getAsLong()) {
            throw new IllegalArgumentException("maximumSizeBytes must not be below minimumSizeBytes");
        }
        if (modifiedAfter.isPresent() && modifiedBefore.isPresent()
                && modifiedBefore.get().isBefore(modifiedAfter.get())) {
            throw new IllegalArgumentException("modifiedBefore must not be before modifiedAfter");
        }
    }

    /** Sprint 1 compatible constructor: extension and minimum-size filtering only. */
    public FileSearchQuery(Set<String> extensions, OptionalLong minimumSizeBytes, int maxResults, int scanLimit) {
        this(extensions, minimumSizeBytes, OptionalLong.empty(), Optional.empty(), Optional.empty(),
                maxResults, scanLimit);
    }

    /**
     * One refinement dimension set of a context follow-up ({@code "only …"}).
     * An absent dimension is inherited from the previous query; a present
     * dimension replaces the previous one entirely (a restated date window
     * replaces start and end together).
     */
    public record Refinement(
            Optional<Set<String>> extensions,
            OptionalLong minimumSizeBytes,
            OptionalLong maximumSizeBytes,
            Optional<Instant> modifiedAfter,
            Optional<Instant> modifiedBefore) {
        public Refinement {
            extensions = Objects.requireNonNull(extensions, "extensions");
            minimumSizeBytes = Objects.requireNonNull(minimumSizeBytes, "minimumSizeBytes");
            maximumSizeBytes = Objects.requireNonNull(maximumSizeBytes, "maximumSizeBytes");
            modifiedAfter = Objects.requireNonNull(modifiedAfter, "modifiedAfter");
            modifiedBefore = Objects.requireNonNull(modifiedBefore, "modifiedBefore");
            extensions.ifPresent(Set::copyOf);
        }
    }

    /**
     * Derives the refined query for a context follow-up ({@code "only …"}):
     * every dimension absent from {@code refinement} is retained from this
     * query, every present dimension replaces this query's value. Limits are
     * carried over unchanged.
     */
    public FileSearchQuery refined(Refinement refinement) {
        return new FileSearchQuery(
                refinement.extensions().orElse(extensions),
                refinement.minimumSizeBytes(),
                refinement.maximumSizeBytes(),
                refinement.modifiedAfter().or(() -> modifiedAfter),
                refinement.modifiedBefore().or(() -> modifiedBefore),
                maxResults, scanLimit);
    }
}
