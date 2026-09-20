package com.jade.api;

import java.util.Objects;
import java.util.Set;

public record ConfiguredApp(String id, String displayName, Set<String> aliases) {
    public ConfiguredApp {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        aliases = Set.copyOf(Objects.requireNonNull(aliases, "aliases"));
    }
}
