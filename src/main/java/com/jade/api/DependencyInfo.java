package com.jade.api;

import java.util.Objects;
import java.util.Optional;

/**
 * One declared Maven dependency as written in the project's {@code pom.xml}.
 * Explicit fields are carried verbatim; property or parent-inherited values
 * stay UNKNOWN rather than being invented — inspection is static and never
 * resolves or downloads anything.
 */
public record DependencyInfo(String groupId, String artifactId, Optional<String> version, Optional<String> scope) {
    /** Marker for a value inspection could not determine statically. */
    public static final String UNKNOWN = "UNKNOWN";

    public DependencyInfo {
        groupId = Objects.requireNonNull(groupId, "groupId");
        artifactId = Objects.requireNonNull(artifactId, "artifactId");
        version = Objects.requireNonNull(version, "version");
        scope = Objects.requireNonNull(scope, "scope");
    }
}
