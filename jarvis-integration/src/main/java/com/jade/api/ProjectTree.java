package com.jade.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Bounded textual representation of the project tree: at most
 * {@link #MAX_LINES} lines and {@link #MAX_DEPTH} levels deep, with
 * truncation reported truthfully instead of silently dropping content.
 */
public record ProjectTree(Path root, List<String> lines, boolean truncated, int maxDepth) implements CommandResult {
    /** Hard bound on the rendered tree so no project can flood the UI. */
    public static final int MAX_LINES = 400;
    /** Depth limit for the rendered tree. */
    public static final int MAX_DEPTH = 6;

    public ProjectTree {
        Objects.requireNonNull(root, "root");
        lines = List.copyOf(lines);
    }

    public ProjectTree(Path root, List<String> lines, boolean truncated) {
        this(root, lines, truncated, MAX_DEPTH);
    }
}
