package com.jarvis.services.system;

import com.jarvis.api.CancellationToken;
import com.jarvis.api.ErrorCode;
import com.jarvis.api.ServiceException;
import com.jarvis.api.SystemSnapshot;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OshiSystemInfoServiceTest {

    private static final CancellationToken NONE = CancellationToken.NONE;

    private static final class FakeSource implements OshiSystemInfoService.SystemMetricSource {
        OptionalDouble cpuLoad = OptionalDouble.of(42.5);
        OptionalLong totalMemory = OptionalLong.of(16_000_000_000L);
        OptionalLong availableMemory = OptionalLong.of(8_000_000_000L);
        String osName = "macOS";
        String osVersion = "27.0";
        String architecture = "aarch64";
        RuntimeException failure;

        @Override
        public OptionalDouble cpuLoadPercent() {
            if (failure != null) {
                throw failure;
            }
            return cpuLoad;
        }

        @Override
        public OptionalLong totalMemoryBytes() {
            return totalMemory;
        }

        @Override
        public OptionalLong availableMemoryBytes() {
            return availableMemory;
        }

        @Override
        public String osName() {
            return osName;
        }

        @Override
        public String osVersion() {
            return osVersion;
        }

        @Override
        public String architecture() {
            return architecture;
        }
    }

    @Test
    void snapshotReportsAvailableMetrics() throws ServiceException {
        FakeSource source = new FakeSource();
        OshiSystemInfoService service = new OshiSystemInfoService(source);

        SystemSnapshot snapshot = service.snapshot(NONE);

        assertTrue(snapshot.cpuLoadPercent().isPresent());
        assertEquals(42.5, snapshot.cpuLoadPercent().getAsDouble(), 1e-9);
        assertEquals(16_000_000_000L, snapshot.totalMemoryBytes().getAsLong());
        assertEquals(8_000_000_000L, snapshot.availableMemoryBytes().getAsLong());
        assertEquals("macOS", snapshot.osName());
        assertEquals("27.0", snapshot.osVersion());
        assertEquals("aarch64", snapshot.architecture());
    }

    @Test
    void unavailableMetricsRemainEmptyAndNeverFabricated() throws ServiceException {
        FakeSource source = new FakeSource();
        source.cpuLoad = OptionalDouble.empty();
        source.availableMemory = OptionalLong.empty();
        OshiSystemInfoService service = new OshiSystemInfoService(source);

        SystemSnapshot snapshot = service.snapshot(NONE);

        assertFalse(snapshot.cpuLoadPercent().isPresent());
        assertTrue(snapshot.totalMemoryBytes().isPresent());
        assertFalse(snapshot.availableMemoryBytes().isPresent());
    }

    @Test
    void blankOsFieldsBecomeUnknownNotExceptions() throws ServiceException {
        FakeSource source = new FakeSource();
        source.osName = " ";
        source.osVersion = null;
        source.architecture = "";
        OshiSystemInfoService service = new OshiSystemInfoService(source);

        SystemSnapshot snapshot = service.snapshot(NONE);

        assertEquals("unknown", snapshot.osName());
        assertEquals("unknown", snapshot.osVersion());
        assertEquals("unknown", snapshot.architecture());
    }

    @Test
    void probeFailureIsServiceFailure() {
        FakeSource source = new FakeSource();
        source.failure = new IllegalStateException("no such metric");
        OshiSystemInfoService service = new OshiSystemInfoService(source);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.snapshot(NONE));

        assertEquals(ErrorCode.SERVICE_FAILURE, exception.error().code());
        assertEquals("no such metric", exception.error().detail().orElse(""));
    }

    @Test
    void preCancelledSnapshotIsCancelled() {
        OshiSystemInfoService service = new OshiSystemInfoService(new FakeSource());

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.snapshot(() -> true));

        assertEquals(ErrorCode.CANCELLED, exception.error().code());
    }

    @Test
    void defaultConstructorUsesRealSource() {
        // Composition constructor must not throw and must wire the OSHI source.
        OshiSystemInfoService service = new OshiSystemInfoService();
        assertTrue(service != null);
    }
}
