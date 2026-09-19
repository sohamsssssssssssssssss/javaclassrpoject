package com.jarvis.services.audio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Deterministic, time-aware greeting selection. Identity personalizes the
 * greeting only — it never grants permissions (AGENTS.md). Tests pin an
 * injected {@link Clock}; no audio or UI is involved.
 */
public final class GreetingService {

    private final Clock clock;

    public GreetingService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The full spoken greeting for the identity at the configured time. */
    public String greetingFor(SpeakerIdentity identity) {
        String part = dayPart();
        return switch (identity) {
            case SOHAM -> part + ", Master Soham. How may I assist you?";
            case VED -> part + ", Master Ved. How may I assist you?";
            case UNKNOWN -> part + ". I am JARVIS, a personal intelligence system developed by Soham and Ved. "
                    + "I don't believe we've met. May I know your name?";
        };
    }

    /** Morning/afternoon/evening by local wall-clock time of the injected clock. */
    public String dayPart() {
        LocalDateTime now = LocalDateTime.now(clock);
        int hour = now.getHour();
        if (hour < 12) {
            return "Good morning";
        }
        if (hour < 18) {
            return "Good afternoon";
        }
        return "Good evening";
    }
}
