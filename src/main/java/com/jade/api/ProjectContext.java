package com.jade.api;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The validated active project of this session: its root, display name and
 * detected build system. Produced only by {@link ProjectService} after the
 * root and its build descriptor have been checked on disk.
 */
public record ProjectContext(Path root, String name, BuildSystem buildSystem, Path descriptorPath)
        implements CommandResult {
    public ProjectContext {
        root = Objects.requireNonNull(root, "root");
        name = Objects.requireNonNull(name, "name");
        buildSystem = Objects.requireNonNull(buildSystem, "buildSystem");
        descriptorPath = Objects.requireNonNull(descriptorPath, "descriptorPath");
    }
}
