package com.jade.services.app;

import java.util.List;
import java.util.Objects;

/**
 * An explicit executable plus argument list. Never a shell string:
 * nothing here is ever passed to {@code sh -c}, {@code bash -c} or
 * {@code cmd /c}; the list goes straight to {@link ProcessBuilder}.
 */
public record LaunchSpec(String description, List<String> command) {
    public LaunchSpec {
        Objects.requireNonNull(description, "description");
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must contain at least an executable");
        }
        if (command.get(0).isBlank()) {
            throw new IllegalArgumentException("executable must not be blank");
        }
    }
}
