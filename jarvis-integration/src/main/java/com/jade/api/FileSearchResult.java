package com.jade.api;

import java.util.List;
import java.util.Objects;

public record FileSearchResult(
        List<FileMatch> matches,
        long visitedFiles,
        boolean resultLimitReached,
        boolean scanLimitReached) implements CommandResult {
    public FileSearchResult {
        matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
        if (visitedFiles < 0) {
            throw new IllegalArgumentException("visitedFiles must not be negative");
        }
    }
}
