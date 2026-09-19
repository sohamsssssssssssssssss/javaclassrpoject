package com.jarvis.services.audio;

import com.jarvis.api.CommandGateway;
import com.jarvis.api.CommandOutcome;
import com.jarvis.api.CommandRequest;
import com.jarvis.api.CommandSubscription;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Owns the bounded voice session state machine and pushes recognized
 * commands through the EXISTING typed pipeline: the transcript string goes
 * to {@link CommandGateway#submit} exactly like typed input, so voice and
 * text converge before tokenization and there is no second voice parser.
 *
 * <p>Session shape: LISTENING for the wake phrase → PROCESSING (speaker
 * match) → SPEAKING (greeting) → LISTENING (command) → EXECUTING (gateway)
 * → SPEAKING (response) → IDLE; ERROR from any state on structured failure.
 * Everything runs on the dedicated session thread, never the JavaFX
 * Application Thread; UI observers receive state/transcript callbacks from
 * this thread and must marshal themselves.</p>
 */
public final class VoiceCommandController implements AutoCloseable {

    /** Length cap on recognized command transcripts; bounds memory and abuse. */
    static final int MAX_COMMAND_LENGTH = 200;
    /** ~4 minutes of 16 kHz mono 16-bit audio; a hard bound on buffered utterance audio. */
    static final int MAX_UTTERANCE_BYTES = 8_000_000;
    /** Silence that ends an utterance; ~0.2 s of audio must precede it. */
    static final int QUIET_WINDOW_MS = 1_200;
    static final int UTTERANCE_TIMEOUT_MS = 12_000;
    static final int GATEWAY_TIMEOUT_SECONDS = 60;
    static final int MIN_UTTERANCE_BYTES = 6_400;

    private final AudioCaptureService capture;
    private final SpeechRecognitionService recognition;
    private final WakePhraseDetector wakeDetector;
    private final SpeechSynthesisService synthesis;
    private final SpeakerIdentificationService speakerIdentification; // nullable
    private final GreetingService greetings;
    private final CommandGateway gateway;
    private final Consumer<VoiceState> onState;
    private final Consumer<String> onTranscript;
    private final Consumer<String> onSpoken; // nullable
    private final Consumer<SpeakerIdentity> onIdentity; // nullable

    private volatile VoiceState state = VoiceState.IDLE;
    private volatile boolean closed;
    private volatile CommandSubscription activeSubscription;
    private volatile Thread sessionThread;

    public VoiceCommandController(
            AudioCaptureService capture,
            SpeechRecognitionService recognition,
            WakePhraseDetector wakeDetector,
            SpeechSynthesisService synthesis,
            SpeakerIdentificationService speakerIdentification,
            GreetingService greetings,
            CommandGateway gateway,
            Consumer<VoiceState> onState,
            Consumer<String> onTranscript,
            Consumer<String> onSpoken,
            Consumer<SpeakerIdentity> onIdentity) {
        this.capture = Objects.requireNonNull(capture, "capture");
        this.recognition = Objects.requireNonNull(recognition, "recognition");
        this.wakeDetector = Objects.requireNonNull(wakeDetector, "wakeDetector");
        this.synthesis = Objects.requireNonNull(synthesis, "synthesis");
        this.speakerIdentification = speakerIdentification; // optional
        this.greetings = Objects.requireNonNull(greetings, "greetings");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.onState = Objects.requireNonNull(onState, "onState");
        this.onTranscript = Objects.requireNonNull(onTranscript, "onTranscript");
        this.onSpoken = onSpoken; // optional
        this.onIdentity = onIdentity; // optional
    }

    /** Current voice session state. */
    public VoiceState state() {
        return state;
    }

    private void transition(VoiceState next) {
        state = next;
        onState.accept(next);
    }

    /**
     * Starts a voice session on the dedicated session thread. Safe to call
     * from the JavaFX Application Thread: it only spawns a thread. Ignored
     * while a session is already running.
     */
    public synchronized void beginSession() {
        if (closed || state != VoiceState.IDLE) {
            return;
        }
        Thread thread = new Thread(this::runSession, "jarvis-voice-session");
        thread.setDaemon(true);
        sessionThread = thread;
        thread.start();
    }

    /** Cancels the running session, if any (the gateway side is cancelled too). */
    @Override
    public synchronized void close() {
        closed = true;
        CommandSubscription subscription = activeSubscription;
        if (subscription != null) {
            subscription.cancel();
        }
        Thread thread = sessionThread;
        if (thread != null) {
            thread.interrupt();
        }
        capture.stop();
    }

    private void runSession() {
        CapturingListener listener = new CapturingListener(recognition);
        try {
            transition(VoiceState.LISTENING);
            capture.start(listener);
            say("Listening for " + TranscriptWakePhraseDetector.expectedPhrase());

            String wakeUtterance = awaitUtterance(listener);
            onTranscript.accept(wakeUtterance);
            if (!wakeDetector.isWakePhrase(wakeUtterance)) {
                fail("I did not hear the wake phrase. Voice session ended.");
                return;
            }

            SpeakerIdentity identity = SpeakerIdentity.UNKNOWN;
            if (speakerIdentification != null && speakerIdentification.hasProfiles()) {
                transition(VoiceState.PROCESSING);
                identity = speakerIdentification.identify(listener.snapshotPcm()).identity();
            }
            if (onIdentity != null) {
                onIdentity.accept(identity);
            }
            listener.resetPcm();
            recognition.reset(); // the spoken greeting must not leak into the command utterance

            transition(VoiceState.SPEAKING);
            say(greetings.greetingFor(identity));

            transition(VoiceState.LISTENING);
            say("Listening");
            String command = awaitUtterance(listener);
            if (command.isBlank()) {
                fail("I did not hear a command. Voice session ended.");
                return;
            }
            String transcript = command.strip();
            if (transcript.length() > MAX_COMMAND_LENGTH) {
                transcript = transcript.substring(0, MAX_COMMAND_LENGTH);
            }
            onTranscript.accept(transcript);

            transition(VoiceState.EXECUTING);
            String response = executeAndWait(transcript);

            transition(VoiceState.SPEAKING);
            say(response);
            transition(VoiceState.IDLE);
        } catch (AudioException e) {
            transition(VoiceState.ERROR);
            say("Voice error: " + e.getMessage());
            transition(VoiceState.IDLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            transition(VoiceState.IDLE);
        } finally {
            capture.stop();
        }
    }

    private void fail(String message) {
        transition(VoiceState.ERROR);
        say(message);
        transition(VoiceState.IDLE);
    }

    /** Feeds the transcript to the EXISTING gateway and returns the text of the spoken response. */
    private String executeAndWait(String transcript) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> response = new java.util.concurrent.atomic.AtomicReference<>("Done.");
        activeSubscription = gateway.submit(
                CommandRequest.create(transcript),
                event -> { /* progress is owned by the UI's existing pipeline display */ },
                outcome -> {
                    response.set(responseText(outcome));
                    done.countDown();
                });
        if (!done.await(GATEWAY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            CommandSubscription subscription = activeSubscription;
            if (subscription != null) {
                subscription.cancel();
            }
            return "The command timed out.";
        }
        return response.get();
    }

    static String responseText(CommandOutcome outcome) {
        String error = outcome.error().map(e -> e.message()).orElse("");
        return switch (outcome.status()) {
            case SUCCEEDED -> outcome.summary().isBlank() ? "Done." : outcome.summary();
            case REJECTED -> "Rejected. " + error;
            case FAILED -> "Failed. " + error;
            case CANCELLED -> "Command cancelled.";
        };
    }

    private void say(String text) {
        if (onSpoken != null) {
            onSpoken.accept(text);
        }
        try {
            synthesis.speak(text);
        } catch (AudioException e) {
            // speaking must never crash the session; the state stays visible
        }
    }

    /**
     * Waits until one utterance has been spoken: audio grew past the minimum
     * and then stayed quiet for {@link #QUIET_WINDOW_MS}. Returns the final
     * STT transcript (empty when nothing intelligible was heard).
     */
    private String awaitUtterance(CapturingListener listener) throws InterruptedException, AudioException {
        long deadline = System.currentTimeMillis() + UTTERANCE_TIMEOUT_MS;
        int lastSize = -1;
        Long quietSince = null;
        while (System.currentTimeMillis() < deadline) {
            AudioException captureError = capture instanceof MicrophoneCaptureService microphone
                    ? microphone.drainSessionError()
                    : null;
            if (captureError != null) {
                throw new AudioException("Microphone stopped during the voice session", captureError);
            }
            AudioException recognitionError = listener.firstError();
            if (recognitionError != null) {
                throw recognitionError;
            }
            int size = listener.pcmSize();
            if (size != lastSize) {
                lastSize = size;
                quietSince = null;
            } else if (size >= MIN_UTTERANCE_BYTES) {
                if (quietSince == null) {
                    quietSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - quietSince >= QUIET_WINDOW_MS) {
                    return recognition.completeUtterance();
                }
            }
            Thread.sleep(80);
        }
        return recognition.completeUtterance();
    }

    /**
     * Capture listener that forwards every chunk to the STT engine (for
     * streaming recognition) and keeps a bounded copy for speaker matching.
     */
    private static final class CapturingListener implements AudioCaptureService.ChunkListener {
        private final SpeechRecognitionService recognition;
        private final java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        private volatile AudioException firstError;

        CapturingListener(SpeechRecognitionService recognition) {
            this.recognition = recognition;
        }

        @Override
        public void onChunk(byte[] chunk, int length) {
            if (length <= 0) {
                return;
            }
            synchronized (this) {
                if (pcm.size() + length <= MAX_UTTERANCE_BYTES) {
                    pcm.write(chunk, 0, length);
                }
            }
            try {
                recognition.acceptPcm(chunk, length);
            } catch (AudioException e) {
                if (firstError == null) {
                    firstError = e;
                }
            }
        }

        synchronized byte[] snapshotPcm() {
            return pcm.toByteArray();
        }

        synchronized void resetPcm() {
            pcm.reset();
        }

        synchronized int pcmSize() {
            return pcm.size();
        }

        AudioException firstError() {
            return firstError;
        }
    }
}
