package com.jade.services.system;

import com.jade.api.CancellationToken;
import com.jade.api.ErrorCode;
import com.jade.api.ServiceException;
import com.jade.api.StructuredError;
import com.jade.api.SystemInfoService;
import com.jade.api.SystemSnapshot;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * System metrics via OSHI (see {@link OshiSystemMetricSource} for the OSHI
 * boundary). Metrics the platform cannot supply are reported as
 * {@link Optional#empty()} — never fabricated percentages or zeros.
 *
 * <p>{@link #snapshot} performs bounded blocking work only (the CPU sampling
 * window inside the source); the gateway's executor keeps it off the JavaFX
 * thread.</p>
 */
public final class OshiSystemInfoService implements SystemInfoService {

    /** Bounded CPU sampling window between OSHI tick reads, in millis. */
    public static final long DEFAULT_SAMPLING_DELAY_MILLIS = 300;

    private final SystemMetricSource source;

    /** Contract composition: real OSHI source against the running host. */
    public OshiSystemInfoService() {
        this(new OshiSystemMetricSource(DEFAULT_SAMPLING_DELAY_MILLIS));
    }

    /** Injection constructor used by tests and custom compositions. */
    public OshiSystemInfoService(SystemMetricSource source) {
        this.source = java.util.Objects.requireNonNull(source, "source");
    }

    @Override
    public SystemSnapshot snapshot(CancellationToken cancellation) throws ServiceException {
        java.util.Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancellationRequested()) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.CANCELLED,
                    "System snapshot cancelled before start",
                    Optional.empty()));
        }
        OptionalDouble cpuLoad;
        OptionalLong totalMemory;
        OptionalLong availableMemory;
        String osName;
        String osVersion;
        String architecture;
        try {
            cpuLoad = source.cpuLoadPercent();
            totalMemory = source.totalMemoryBytes();
            availableMemory = source.availableMemoryBytes();
            osName = orUnknown(source.osName());
            osVersion = orUnknown(source.osVersion());
            architecture = orUnknown(source.architecture());
        } catch (RuntimeException e) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.SERVICE_FAILURE,
                    "System information is unavailable on this platform",
                    Optional.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())), e);
        }
        if (cancellation.isCancellationRequested()) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.CANCELLED,
                    "System snapshot cancelled",
                    Optional.empty()));
        }
        return new SystemSnapshot(
                Instant.now(),
                osName,
                osVersion,
                architecture,
                cpuLoad,
                totalMemory,
                availableMemory);
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    /**
     * Narrow metrics seam so the snapshot logic is testable without OSHI
     * types; {@link OshiSystemMetricSource} is the production implementation.
     */
    public interface SystemMetricSource {
        /** System CPU load in percent, or empty when not computable. */
        OptionalDouble cpuLoadPercent();

        OptionalLong totalMemoryBytes();

        OptionalLong availableMemoryBytes();

        String osName();

        String osVersion();

        String architecture();
    }
}
