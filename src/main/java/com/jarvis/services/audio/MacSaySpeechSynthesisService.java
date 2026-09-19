package com.jarvis.services.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * macOS text-to-speech adapter over the built-in {@code /usr/bin/say}.
 * Process argument lists are explicit and never shell-interpolated; the
 * voice and speaking rate are fixed flags. Other platforms throw a
 * structured, visible {@link AudioException} rather than pretending to work.
 */
public final class MacSaySpeechSynthesisService implements SpeechSynthesisService {

    static final Path SAY = Path.of("/usr/bin/say");
    static final String DEFAULT_VOICE = "Samantha";
    static final int DEFAULT_RATE_WORDS_PER_MINUTE = 175;

    private final ProcessBuilder protocol;
    private final Process currentProcess;

    private MacSaySpeechSynthesisService(ProcessBuilder protocol, Process currentProcess) {
        this.protocol = protocol;
        this.currentProcess = currentProcess;
    }

    public MacSaySpeechSynthesisService() {
        this.protocol = null;
        this.currentProcess = null;
    }

    /** Test seam: runs the given protocol instead of real processes. */
    static MacSaySpeechSynthesisService forProtocol(ProcessBuilder protocol, Process process) {
        return new MacSaySpeechSynthesisService(protocol, process);
    }

    @Override
    public void speak(String text) throws AudioException {
        if (text == null || text.isBlank()) {
            return;
        }
        List<String> command = commandFor(text.strip());
        ProcessBuilder builder = protocol != null ? protocol : new ProcessBuilder(command);
        try {
            Process process = protocol != null ? currentProcess : builder.start();
            boolean finished = process.waitFor(2, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new AudioException("Speech synthesis timed out after "
                        + Duration.ofSeconds(120).toSeconds() + " s");
            }
            if (process.exitValue() != 0) {
                throw new AudioException("Speech synthesis failed with exit code " + process.exitValue());
            }
        } catch (IOException e) {
            throw new AudioException("Could not start the speech synthesizer (/usr/bin/say): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AudioException("Speech synthesis was interrupted", e);
        }
    }

    /** The exact argument list this adapter runs; recorded for the feasibility gate. */
    static List<String> commandFor(String text) {
        List<String> command = new ArrayList<>();
        command.add(SAY.toString());
        command.add("-v");
        command.add(DEFAULT_VOICE);
        command.add("-r");
        command.add(String.valueOf(DEFAULT_RATE_WORDS_PER_MINUTE));
        command.add(text);
        return List.copyOf(command);
    }

    @Override
    public void stop() {
        Process process = currentProcess;
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    /** Verifies the platform can synthesize at construction time; fails visibly otherwise. */
    public static MacSaySpeechSynthesisService createForCurrentPlatform() throws AudioException {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("mac")) {
            throw new AudioException("Speech synthesis is not yet supported on " + System.getProperty("os.name")
                    + ". Voice output is currently macOS-only (see docs/AUDIO_FEASIBILITY.md).");
        }
        if (!Files.isRegularFile(SAY)) {
            throw new AudioException("macOS speech synthesizer not found at " + SAY);
        }
        return new MacSaySpeechSynthesisService();
    }

    @Override
    public void close() {
        stop();
    }
}
