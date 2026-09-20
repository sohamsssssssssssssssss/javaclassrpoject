package com.jade.services.audio;

/**
 * Live microphone capture of 16 kHz, 16-bit, mono, little-endian PCM.
 *
 * <p>Implementations must never block the calling thread for capture work
 * longer than one buffer and must surface device/permission failures as
 * {@link AudioException} rather than silently ignoring them. Buffers are
 * bounded: a listener never receives more than {@code maxChunkBytes} bytes
 * per callback.</p>
 */
public interface AudioCaptureService extends AutoCloseable {

    /**
     * Opens the microphone and begins delivering PCM chunks to the listener
     * on a dedicated capture thread. Fails fast with a structured
     * {@link AudioException} when the line is unavailable or permission is
     * denied; the caller's thread is never blocked for the duration of the
     * session.
     */
    void start(ChunkListener listener) throws AudioException;

    /** Stops capture and releases the line. Idempotent. Never throws. */
    void stop();

    /** Equivalent to {@link #stop()}; narrows {@link AutoCloseable#close()} to never throw. */
    @Override
    void close();

    /** Size of each delivered PCM chunk in bytes (a whole number of frames). */
    int maxChunkBytes();

    /** The PCM format produced: 16 kHz sample rate, 16-bit signed, mono, little-endian. */
    static AudioFormat format() {
        return new AudioFormat(16_000.0f, 16, 1, true, true);
    }

    /** Receives bounded PCM chunks on the capture thread. */
    interface ChunkListener {
        void onChunk(byte[] pcm, int length);
    }

    /** Minimal description of the PCM format produced by capture. */
    record AudioFormat(float sampleRate, int sampleSizeBits, int channels, boolean signed, boolean littleEndian) {
    }
}
