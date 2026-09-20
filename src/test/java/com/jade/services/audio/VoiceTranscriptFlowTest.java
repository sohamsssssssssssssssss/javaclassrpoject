package com.jade.services.audio;

import com.jade.api.CommandGateway;
import com.jade.api.CommandOutcome;
import com.jade.api.CommandRequest;
import com.jade.api.CommandStatus;
import com.jade.api.CommandSubscription;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transcript forwarding and voice/text convergence: a scripted fake STT
 * feeds a transcript that must reach the EXISTING gateway as a
 * {@link CommandRequest} identical to typed input, and the spoken response
 * must come from the outcome. No microphone, model or JavaFX is involved.
 */
class VoiceTranscriptFlowTest {

    /** Gateway double that records submissions and completes them immediately. */
    static final class RecordingGateway implements CommandGateway {
        final List<String> submitted = new ArrayList<>();
        private final String responseSummary;
        private final CommandStatus responseStatus;

        RecordingGateway(String responseSummary, CommandStatus responseStatus) {
            this.responseSummary = responseSummary;
            this.responseStatus = responseStatus;
        }

        @Override
        public CommandSubscription submit(CommandRequest request,
                                          Consumer<com.jade.api.ProgressEvent> onProgress,
                                          Consumer<CommandOutcome> onComplete) {
            submitted.add(request.originalText());
            onComplete.accept(new CommandOutcome(
                    request.id(), responseStatus, responseSummary,
                    Optional.empty(), Optional.empty(), request.submittedAt(), Instant.now()));
            return new CommandSubscription() {
                @Override
                public java.util.UUID requestId() {
                    return request.id();
                }

                @Override
                public void cancel() {
                }

                @Override
                public boolean isCancellationRequested() {
                    return false;
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public void close() {
        }
    }

    /** STT double: returns a scripted transcript on finalize. */
    static final class ScriptedRecognition implements SpeechRecognitionService {
        private final java.util.Deque<String> scripted = new java.util.ArrayDeque<>();
        final List<byte[]> accepted = new ArrayList<>();
        private boolean resetCalled;

        void scriptNext(String transcript) {
            scripted.add(transcript);
        }

        @Override
        public void acceptPcm(byte[] pcm, int length) {
            accepted.add(java.util.Arrays.copyOf(pcm, length));
        }

        @Override
        public String completeUtterance() {
            return scripted.isEmpty() ? "" : scripted.poll();
        }

        @Override
        public String partialTranscript() {
            return "";
        }

        @Override
        public void reset() {
            resetCalled = true;
        }

        boolean wasReset() {
            return resetCalled;
        }

        @Override
        public void close() {
        }
    }

    /**
     * Capture double: emits a wake burst immediately and a command burst
     * ~2.5 s later on a daemon thread, mimicking two spoken utterances with
     * silence between them. Never blocks the controller beyond one chunk.
     */
    static final class ScriptedCapture implements AudioCaptureService {
        private volatile AudioCaptureService.ChunkListener listener;
        private Thread emitter;

        @Override
        public void start(ChunkListener listener) {
            this.listener = listener;
            emitter = new Thread(() -> {
                try {
                    listener.onChunk(new byte[MIN_BYTES], MIN_BYTES);
                    Thread.sleep(250);
                    listener.onChunk(new byte[MIN_BYTES], MIN_BYTES);
                    Thread.sleep(250);
                    listener.onChunk(new byte[MIN_BYTES], MIN_BYTES);
                    Thread.sleep(1_600); // silence, then the second utterance
                    listener.onChunk(new byte[MIN_BYTES], MIN_BYTES);
                    Thread.sleep(250);
                    listener.onChunk(new byte[MIN_BYTES], MIN_BYTES);
                    Thread.sleep(250);
                    listener.onChunk(new byte[MIN_BYTES], MIN_BYTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "scripted-audio");
            emitter.setDaemon(true);
            emitter.start();
        }

        @Override
        public void stop() {
            Thread thread = emitter;
            if (thread != null) {
                thread.interrupt();
                emitter = null;
            }
            listener = null;
        }

        @Override
        public int maxChunkBytes() {
            return 3_200;
        }

        @Override
        public void close() {
            stop();
        }
    }

    static final class ScriptedSynthesis implements SpeechSynthesisService {
        final List<String> spoken = new ArrayList<>();

        @Override
        public void speak(String text) {
            spoken.add(text);
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
        }
    }

    private static final int MIN_BYTES = VoiceCommandController.MIN_UTTERANCE_BYTES;

    @Test
    void wakeThenCommandReachesTheExistingGateway() throws Exception {
        RecordingGateway gateway = new RecordingGateway("Command accepted", CommandStatus.SUCCEEDED);
        ScriptedRecognition recognition = new ScriptedRecognition();
        recognition.scriptNext("hello jade");      // wake utterance
        recognition.scriptNext("system status");     // command utterance
        ScriptedSynthesis synthesis = new ScriptedSynthesis();

        List<VoiceState> states = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> transcripts = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch idle = new CountDownLatch(1);

        VoiceCommandController controller = new VoiceCommandController(
                new ScriptedCapture(),
                recognition,
                new TranscriptWakePhraseDetector(),
                synthesis,
                null, // no speaker service: identity falls back to UNKNOWN
                new GreetingService(java.time.Clock.systemUTC()),
                gateway,
                state -> {
                    states.add(state);
                    if (state == VoiceState.IDLE) {
                        idle.countDown();
                    }
                },
                transcripts::add,
                null,
                null);

        controller.beginSession();
        assertTrue(idle.await(10, TimeUnit.SECONDS), "session should finish");

        // The EXISTING gateway received the spoken command exactly like typed text.
        assertEquals(List.of("system status"), gateway.submitted);

        // The wake phrase and command were surfaced as transcripts.
        assertTrue(transcripts.contains("hello jade"));
        assertTrue(transcripts.contains("system status"));

        // A greeting was spoken between wake and response.
        assertTrue(synthesis.spoken.stream().anyMatch(s -> s.contains("How may I assist you?")
                || s.contains("May I know your name?")));
        // The response was spoken.
        assertTrue(synthesis.spoken.contains("Command accepted"));
        // Recognition was reset after the wake utterance (greeting contamination guard).
        assertTrue(recognition.wasReset());
    }

    @Test
    void failedOutcomeProducesSpokenFailure() throws Exception {
        RecordingGateway gateway = new RecordingGateway("Unsupported command", CommandStatus.REJECTED);
        ScriptedRecognition recognition = new ScriptedRecognition();
        recognition.scriptNext("hello jade");
        recognition.scriptNext("frobnicate the widget");
        ScriptedSynthesis synthesis = new ScriptedSynthesis();
        CountDownLatch idle = new CountDownLatch(1);

        VoiceCommandController controller = new VoiceCommandController(
                new ScriptedCapture(),
                recognition,
                new TranscriptWakePhraseDetector(),
                synthesis,
                null,
                new GreetingService(java.time.Clock.systemUTC()),
                gateway,
                state -> {
                    if (state == VoiceState.IDLE) {
                        idle.countDown();
                    }
                },
                ignored -> {
                },
                null,
                null);

        controller.beginSession();
        assertTrue(idle.await(10, TimeUnit.SECONDS));
        assertEquals(List.of("frobnicate the widget"), gateway.submitted);
        assertTrue(synthesis.spoken.stream().anyMatch(s -> s.startsWith("Rejected. ")));
    }

    @Test
    void nonWakePhraseEndsWithoutSubmitting() throws Exception {
        RecordingGateway gateway = new RecordingGateway("unused", CommandStatus.SUCCEEDED);
        ScriptedRecognition recognition = new ScriptedRecognition();
        recognition.scriptNext("tell me a joke"); // not the wake phrase
        ScriptedSynthesis synthesis = new ScriptedSynthesis();
        CountDownLatch idle = new CountDownLatch(1);

        VoiceCommandController controller = new VoiceCommandController(
                new ScriptedCapture(),
                recognition,
                new TranscriptWakePhraseDetector(),
                synthesis,
                null,
                new GreetingService(java.time.Clock.systemUTC()),
                gateway,
                state -> {
                    if (state == VoiceState.IDLE) {
                        idle.countDown();
                    }
                },
                ignored -> {
                },
                null,
                null);

        controller.beginSession();
        assertTrue(idle.await(10, TimeUnit.SECONDS));
        assertTrue(gateway.submitted.isEmpty(), "no command may reach the gateway without a wake phrase");
    }

    @Test
    void responseTextCoversEveryOutcomeStatus() {
        Instant now = Instant.now();
        java.util.UUID id = java.util.UUID.randomUUID();
        assertEquals("Done.", VoiceCommandController.responseText(new CommandOutcome(
                id, CommandStatus.SUCCEEDED, "", Optional.empty(), Optional.empty(), now, now)));
        assertEquals("system status", VoiceCommandController.responseText(new CommandOutcome(
                id, CommandStatus.SUCCEEDED, "system status", Optional.empty(), Optional.empty(), now, now)));
        assertEquals("Command cancelled.", VoiceCommandController.responseText(new CommandOutcome(
                id, CommandStatus.CANCELLED, "", Optional.empty(), Optional.empty(), now, now)));
    }
}
