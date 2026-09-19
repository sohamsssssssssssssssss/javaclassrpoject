package com.jarvis.services.audio;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TTS abstraction behavior without running real synthesis: the exact
 * argument list is deterministic (never shell-interpolated) and unsupported
 * platforms fail visibly. Real audible output was verified on-target during
 * the feasibility gate and is documented in docs/AUDIO_FEASIBILITY.md.
 */
class MacSaySpeechSynthesisServiceTest {

    @Test
    void argumentListIsExplicitAndDeterministic() {
        List<String> command = MacSaySpeechSynthesisService.commandFor("Good morning, Master Soham.");
        assertEquals(List.of("/usr/bin/say", "-v", "Samantha", "-r", "175", "Good morning, Master Soham."), command);
    }

    @Test
    void textWithQuotesIsPassedAsOneArgument() {
        List<String> command = MacSaySpeechSynthesisService.commandFor("say \"hello\"; rm -rf /");
        // The whole hostile string is one argument to say, never a shell command.
        assertEquals("say \"hello\"; rm -rf /", command.get(command.size() - 1));
        assertEquals(Path.of("/usr/bin/say"), Path.of(command.getFirst()));
    }

    @Test
    void speakIgnoresBlankTextWithoutStartingAProcess() throws Exception {
        // The fake-protocol constructor would fail on any process use; blank text must return silently.
        MacSaySpeechSynthesisService service = MacSaySpeechSynthesisService.forProtocol(null, null);
        service.speak("   ");
        service.speak(null);
    }

    @Test
    void unsupportedPlatformFailsVisibly() {
        String original = System.getProperty("os.name");
        System.setProperty("os.name", "Linux");
        try {
            AudioException failure = assertThrows(AudioException.class,
                    MacSaySpeechSynthesisService::createForCurrentPlatform);
            assertTrue(failure.getMessage().contains("macOS-only") || failure.getMessage().contains("not yet supported"));
        } finally {
            System.setProperty("os.name", original);
        }
    }
}
