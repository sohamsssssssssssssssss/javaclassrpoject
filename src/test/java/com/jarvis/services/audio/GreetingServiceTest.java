package com.jarvis.services.audio;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic greeting tests over a pinned clock — no audio, no TTS. */
class GreetingServiceTest {

    private static final ZoneId ZONE = ZoneId.of("America/New_York");

    private static Clock at(String localDateTime) {
        LocalDateTime time = LocalDateTime.parse(localDateTime);
        return Clock.fixed(time.atZone(ZONE).toInstant(), ZONE);
    }

    @Test
    void morningIsBeforeNoon() {
        GreetingService greetings = new GreetingService(at("2026-09-18T07:14:59"));
        assertEquals("Good morning", greetings.dayPart());
        assertTrue(greetings.greetingFor(SpeakerIdentity.SOHAM).startsWith("Good morning, Master Soham."));
    }

    @Test
    void noonIsAfternoon() {
        GreetingService greetings = new GreetingService(at("2026-09-18T12:00:00"));
        assertEquals("Good afternoon", greetings.dayPart());
    }

    @Test
    void eveningStartsAt18() {
        GreetingService greetings = new GreetingService(at("2026-09-18T18:00:00"));
        assertEquals("Good evening", greetings.dayPart());
    }

    @Test
    void lastMinuteOfMorningIsMorning() {
        GreetingService greetings = new GreetingService(at("2026-09-18T11:59:59"));
        assertEquals("Good morning", greetings.dayPart());
    }

    @Test
    void lastMinuteOfAfternoonIsAfternoon() {
        GreetingService greetings = new GreetingService(at("2026-09-18T17:59:59"));
        assertEquals("Good afternoon", greetings.dayPart());
    }

    @Test
    void sohamGreetingUsesMasterTitle() {
        GreetingService greetings = new GreetingService(at("2026-09-18T09:00:00"));
        assertEquals("Good morning, Master Soham. How may I assist you?",
                greetings.greetingFor(SpeakerIdentity.SOHAM));
    }

    @Test
    void vedGreetingUsesMasterTitle() {
        GreetingService greetings = new GreetingService(at("2026-09-18T15:00:00"));
        assertEquals("Good afternoon, Master Ved. How may I assist you?",
                greetings.greetingFor(SpeakerIdentity.VED));
    }

    @Test
    void unknownGreetingIntroducesJarvis() {
        GreetingService greetings = new GreetingService(at("2026-09-18T20:00:00"));
        String greeting = greetings.greetingFor(SpeakerIdentity.UNKNOWN);
        assertTrue(greeting.startsWith("Good evening. I am JARVIS"));
        assertTrue(greeting.contains("developed by Soham and Ved"));
        assertTrue(greeting.contains("May I know your name?"));
    }
}
