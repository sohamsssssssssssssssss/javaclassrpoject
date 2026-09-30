package com.jade.api;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;

public record SystemSnapshot(
        Instant capturedAt,
        String osName,
        String osVersion,
        String architecture,
        OptionalDouble cpuLoadPercent,
        OptionalLong totalMemoryBytes,
        OptionalLong availableMemoryBytes) implements CommandResult {
    public SystemSnapshot {
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(osVersion, "osVersion");
        Objects.requireNonNull(architecture, "architecture");
        cpuLoadPercent = Objects.requireNonNull(cpuLoadPercent, "cpuLoadPercent");
        totalMemoryBytes = Objects.requireNonNull(totalMemoryBytes, "totalMemoryBytes");
        availableMemoryBytes = Objects.requireNonNull(availableMemoryBytes, "availableMemoryBytes");
    }
}
