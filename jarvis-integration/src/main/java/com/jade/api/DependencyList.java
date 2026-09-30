package com.jade.api;

import java.util.List;
import java.util.Objects;

/**
 * Renderable list of the active project's declared dependencies — the typed
 * answer to "what dependencies does it use". Empty means the POM declares
 * none; it is never a stand-in for an unreadable POM.
 */
public record DependencyList(List<DependencyInfo> dependencies) implements CommandResult {
    public DependencyList {
        dependencies = List.copyOf(dependencies);
        Objects.requireNonNull(dependencies, "dependencies");
    }
}
