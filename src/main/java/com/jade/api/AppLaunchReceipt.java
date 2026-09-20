package com.jade.api;

import java.time.Instant;
import java.util.Objects;

public record AppLaunchReceipt(String appId, String displayName, Instant launchedAt) implements CommandResult {
    public AppLaunchReceipt {
        Objects.requireNonNull(appId, "appId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(launchedAt, "launchedAt");
    }
}
