package com.jade.ui;

import com.jade.api.SystemSnapshot;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import static org.junit.jupiter.api.Assertions.*;

class SystemResultViewTest {
    private static SystemSnapshot snapshot(OptionalDouble cpu, OptionalLong total, OptionalLong available) {
        return new SystemSnapshot(Instant.EPOCH, "Test OS", "1", "arm64", cpu, total, available);
    }

    @Test
    void derivesUsedMemoryFromTheAvailableReading() {
        var value = snapshot(OptionalDouble.of(31.1), OptionalLong.of(1600), OptionalLong.of(450));
        assertEquals(0.71875, SystemResultView.memoryUsage(value).orElseThrow());
        assertEquals(0.311, SystemResultView.cpuUsage(value).orElseThrow(), 0.000001);
    }

    @Test
    void missingAndInvalidReadingsStayUnavailable() {
        assertTrue(SystemResultView.memoryUsage(snapshot(OptionalDouble.empty(), OptionalLong.empty(), OptionalLong.of(1))).isEmpty());
        assertTrue(SystemResultView.memoryUsage(snapshot(OptionalDouble.empty(), OptionalLong.of(0), OptionalLong.of(0))).isEmpty());
        assertTrue(SystemResultView.memoryUsage(snapshot(OptionalDouble.empty(), OptionalLong.of(10), OptionalLong.of(11))).isEmpty());
        assertTrue(SystemResultView.cpuUsage(snapshot(OptionalDouble.of(Double.NaN), OptionalLong.empty(), OptionalLong.empty())).isEmpty());
        assertTrue(SystemResultView.cpuUsage(snapshot(OptionalDouble.empty(), OptionalLong.empty(), OptionalLong.empty())).isEmpty());
    }
}
