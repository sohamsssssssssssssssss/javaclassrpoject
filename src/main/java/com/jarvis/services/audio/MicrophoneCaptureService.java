package com.jarvis.services.audio;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.TargetDataLine;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java Sound API microphone capture at 16 kHz/16-bit/mono/LE. Owns its
 * capture thread; the caller's thread is never blocked for capture work.
 * Device/permission failures surface as structured {@link AudioException}s:
 * construction and {@link #start} throw synchronously where the failure is
 * immediate, and a mid-session line failure is recorded in
 * {@link #drainSessionError()} for the voice loop to observe.
 */
public final class MicrophoneCaptureService implements AudioCaptureService {

    /** Bounded internal line buffer: one second of 16 kHz mono 16-bit audio. */
    private static final int TARGET_LINE_BUFFER_BYTES = 16_000 * 2;

    private final TargetDataLine line;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<AudioException> sessionError = new AtomicReference<>();
    private volatile Thread captureThread;

    public MicrophoneCaptureService() throws AudioException {
        this(defaultFormat());
    }

    MicrophoneCaptureService(javax.sound.sampled.AudioFormat format) throws AudioException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        try {
            this.line = (TargetDataLine) AudioSystem.getLine(info);
        } catch (javax.sound.sampled.LineUnavailableException e) {
            throw new AudioException("Microphone is unavailable (in use, disabled, or permission denied): "
                    + e.getMessage(), e);
        } catch (SecurityException e) {
            throw new AudioException("Microphone access denied by the operating system: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new AudioException("No microphone supporting 16 kHz mono capture was found: " + e.getMessage(), e);
        }
    }

    private static javax.sound.sampled.AudioFormat defaultFormat() {
        AudioCaptureService.AudioFormat f = AudioCaptureService.format();
        return new javax.sound.sampled.AudioFormat(
                f.sampleRate(), f.sampleSizeBits(), f.channels(), f.signed(), f.littleEndian());
    }

    @Override
    public void start(ChunkListener listener) throws AudioException {
        Objects.requireNonNull(listener, "listener");
        if (!running.compareAndSet(false, true)) {
            return; // already capturing
        }
        try {
            line.open(defaultFormat(), TARGET_LINE_BUFFER_BYTES);
        } catch (javax.sound.sampled.LineUnavailableException e) {
            running.set(false);
            throw new AudioException("Could not open the microphone line: " + e.getMessage(), e);
        } catch (SecurityException e) {
            running.set(false);
            throw new AudioException("Microphone access denied by the operating system: " + e.getMessage(), e);
        }
        sessionError.set(null);
        line.addLineListener(new LineListener() {
            @Override
            public void update(LineEvent event) {
                if (running.get() && (event.getType() == LineEvent.Type.CLOSE
                        || event.getType() == LineEvent.Type.STOP)) {
                    sessionError.compareAndSet(null,
                            new AudioException("Microphone line closed or stopped unexpectedly"));
                }
            }
        });
        Thread thread = new Thread(() -> captureLoop(listener), "jarvis-mic-capture");
        thread.setDaemon(true);
        captureThread = thread;
        thread.start();
    }

    private void captureLoop(ChunkListener listener) {
        int chunkBytes = maxChunkBytes();
        byte[] buffer = new byte[chunkBytes];
        try {
            line.start();
        } catch (SecurityException e) {
            sessionError.compareAndSet(null,
                    new AudioException("Microphone access denied: " + e.getMessage(), e));
            running.set(false);
            return;
        }
        while (running.get()) {
            int read = line.read(buffer, 0, chunkBytes);
            if (read <= 0) {
                if (running.get()) {
                    sessionError.compareAndSet(null,
                            new AudioException("Microphone returned no audio (device stopped or disconnected)"));
                }
                break;
            }
            try {
                listener.onChunk(buffer, read);
            } catch (RuntimeException e) {
                sessionError.compareAndSet(null,
                        new AudioException("Audio listener failed: " + e.getMessage(), e));
            }
        }
    }

    /**
     * Returns and clears the first error observed since {@link #start}, if any.
     * The voice loop polls this so a dead line ends the session visibly.
     */
    public AudioException drainSessionError() {
        return sessionError.getAndSet(null);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            line.stop();
            line.flush();
            line.close();
        } catch (RuntimeException e) {
            // closing an already-dead line must not mask earlier errors
        }
        Thread thread = captureThread;
        if (thread != null) {
            thread.interrupt();
            captureThread = null;
        }
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public int maxChunkBytes() {
        return 3_200; // 100 ms of 16 kHz mono 16-bit audio
    }
}
