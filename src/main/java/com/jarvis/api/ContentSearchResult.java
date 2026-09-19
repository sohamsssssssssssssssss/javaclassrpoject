package com.jarvis.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Application-level result of a document content search. Wraps the service
 * layer's {@code ContentSearchService.SearchResult} without leaking service
 * types into the API; the extraction failure of each document is preserved as
 * its own entry so a failed extraction never masquerades as "zero matches".
 */
public record ContentSearchResult(
        String query,
        List<DocumentHit> documents,
        int totalMatches,
        String appliedLimits) implements CommandResult {
    public ContentSearchResult {
        Objects.requireNonNull(query, "query");
        documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
        if (totalMatches < 0) {
            throw new IllegalArgumentException("totalMatches must not be negative");
        }
        Objects.requireNonNull(appliedLimits, "appliedLimits");
    }

    /** One searched document with its match count, snippet or failure reason. */
    public record DocumentHit(
            java.nio.file.Path path,
            int matchCount,
            Optional<String> snippet,
            boolean extractionFailed,
            Optional<String> failureReason) {
        public DocumentHit {
            Objects.requireNonNull(path, "path");
            snippet = Objects.requireNonNull(snippet, "snippet");
            failureReason = Objects.requireNonNull(failureReason, "failureReason");
        }

        public static DocumentHit matched(java.nio.file.Path path, int matchCount, Optional<String> snippet) {
            return new DocumentHit(path, matchCount, snippet, false, Optional.empty());
        }

        public static DocumentHit noMatch(java.nio.file.Path path) {
            return new DocumentHit(path, 0, Optional.empty(), false, Optional.empty());
        }

        public static DocumentHit failed(java.nio.file.Path path, String reason) {
            return new DocumentHit(path, 0, Optional.empty(), true, Optional.of(reason));
        }
    }
}
