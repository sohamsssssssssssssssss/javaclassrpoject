package com.jarvis.api;

import java.util.List;
import java.util.Objects;

public record HistoryResult(List<HistoryEntry> entries) implements CommandResult {
    public HistoryResult {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }
}
