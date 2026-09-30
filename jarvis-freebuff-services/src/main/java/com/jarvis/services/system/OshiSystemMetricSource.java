package com.jarvis.services.system;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;

import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * The only class that touches OSHI. CPU load is sampled over a bounded
 * window between two tick reads; "not computable" signals (negative or
 * non-finite load, zero memory) surface as {@link Optional#empty()} so
 * percentages are never fabricated. Memory metrics come from the OSHI
 * memory layer; storage/battery metrics are out of sprint 1 scope (the
 * frozen {@code SystemSnapshot} record carries no such fields).
 */
final class OshiSystemMetricSource implements OshiSystemInfoService.SystemMetricSource {

    private final long samplingDelayMillis;

    OshiSystemMetricSource(long samplingDelayMillis) {
        if (samplingDelayMillis < 0) {
            throw new IllegalArgumentException("samplingDelayMillis must not be negative");
        }
        this.samplingDelayMillis = samplingDelayMillis;
    }

    @Override
    public OptionalDouble cpuLoadPercent() {
        SystemInfo systemInfo = new SystemInfo();
        HardwareAbstractionLayer hardware = systemInfo.getHardware();
        CentralProcessor processor = hardware.getProcessor();
        if (processor == null) {
            return OptionalDouble.empty();
        }
        long[] before;
        try {
            before = processor.getSystemCpuLoadTicks();
        } catch (RuntimeException e) {
            return OptionalDouble.empty();
        }
        sleepBeforeSecondTick();
        double load;
        try {
            load = processor.getSystemCpuLoadBetweenTicks(before);
        } catch (RuntimeException e) {
            return OptionalDouble.empty();
        }
        // OSHI signals "not computable" with negative or non-finite values.
        if (Double.isNaN(load) || Double.isInfinite(load) || load < 0d || load > 1d) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(load * 100d);
    }

    private void sleepBeforeSecondTick() {
        if (samplingDelayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(samplingDelayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public OptionalLong totalMemoryBytes() {
        long total = systemInfo().getHardware().getMemory().getTotal();
        return total > 0 ? OptionalLong.of(total) : OptionalLong.empty();
    }

    @Override
    public OptionalLong availableMemoryBytes() {
        long available = systemInfo().getHardware().getMemory().getAvailable();
        return available > 0 ? OptionalLong.of(available) : OptionalLong.empty();
    }

    @Override
    public String osName() {
        OperatingSystem os = systemInfo().getOperatingSystem();
        return os == null ? null : os.getFamily();
    }

    @Override
    public String osVersion() {
        OperatingSystem os = systemInfo().getOperatingSystem();
        return os == null ? null : os.getVersionInfo().getVersion();
    }

    @Override
    public String architecture() {
        CentralProcessor processor = systemInfo().getHardware().getProcessor();
        if (processor == null || processor.getProcessorIdentifier() == null) {
            return null;
        }
        // OSHI's identifier is a free-form vendor string; fall back to os.arch
        // which is the normalized architecture the JVM runs with.
        String identifierArchitecture = processor.getProcessorIdentifier().toString();
        String systemArchitecture = System.getProperty("os.arch", "");
        return systemArchitecture.isBlank() ? identifierArchitecture : systemArchitecture;
    }

    private SystemInfo systemInfo() {
        return new SystemInfo();
    }
}
