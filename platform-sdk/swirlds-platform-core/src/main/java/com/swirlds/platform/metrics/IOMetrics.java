// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.metrics;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_16_0;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.io.IOStatsFactory;
import com.swirlds.platform.system.io.IOStatsReader;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collection of disk I/O metrics from the operating system.
 * <p>
 * This class focuses exclusively on actual disk I/O (not cached reads/writes),
 * matching the behavior of iotop. Metrics tracked:
 * <ul>
 *     <li>Disk bytes read and written - cumulative counters from storage device</li>
 *     <li>Cancelled write bytes - bytes scheduled for write but cancelled</li>
 *     <li>Read and write rates - bytes per second calculated from disk I/O deltas</li>
 * </ul>
 * <p>
 * These metrics are collected from {@code /proc/[pid]/io} on Linux systems, using
 * the same data sources as iotop:
 * <ul>
 *     <li>{@code read_bytes} - actual bytes read from storage</li>
 *     <li>{@code write_bytes} - actual bytes written to storage</li>
 *     <li>{@code cancelled_write_bytes} - cancelled write operations</li>
 * </ul>
 * <p>
 * <b>High-Frequency Sampling:</b> To provide accurate rate calculations, this implementation
 * samples I/O stats at high frequency (every 100ms) and reports the average rate to the
 * metrics framework. This ensures that bursty I/O activity is captured accurately, even when
 * metrics are only reported once per second.
 * <p>
 * On non-Linux platforms, metrics will be registered but remain at zero until
 * platform-specific implementations are added.
 * <p>
 * Metrics exposed:
 * <ul>
 *     <li>ioDiskBytesRead - cumulative disk reads in bytes</li>
 *     <li>ioDiskBytesWritten - cumulative disk writes in bytes</li>
 *     <li>ioCancelledWriteBytes - cumulative cancelled writes in bytes</li>
 *     <li>ioDiskReadRate - average disk read rate in bytes/second (from 100ms samples)</li>
 *     <li>ioDiskWriteRate - average disk write rate in bytes/second (from 100ms samples)</li>
 * </ul>
 */
public final class IOMetrics {

    private static final RunningAverageMetric.Config DISK_BYTES_READ_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "ioDiskBytesRead")
            .withDescription("cumulative bytes read from storage device")
            .withFormat(FORMAT_16_0)
            .withUnit("bytes")
            .withHalfLife(0.0);
    private final RunningAverageMetric diskBytesRead;

    private static final RunningAverageMetric.Config DISK_BYTES_WRITTEN_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "ioDiskBytesWritten")
            .withDescription("cumulative bytes written to storage device")
            .withFormat(FORMAT_16_0)
            .withUnit("bytes")
            .withHalfLife(0.0);
    private final RunningAverageMetric diskBytesWritten;

    private static final RunningAverageMetric.Config CANCELLED_WRITE_BYTES_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "ioCancelledWriteBytes")
            .withDescription("cumulative bytes scheduled for write but cancelled")
            .withFormat(FORMAT_16_0)
            .withUnit("bytes")
            .withHalfLife(0.0);
    private final RunningAverageMetric cancelledWriteBytes;

    private static final RunningAverageMetric.Config DISK_READ_RATE_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "ioDiskReadRate")
            .withDescription("current disk read rate in bytes per second")
            .withFormat(FORMAT_16_0)
            .withUnit("bytes/s")
            .withHalfLife(0.0);
    private final RunningAverageMetric diskReadRate;

    private static final RunningAverageMetric.Config DISK_WRITE_RATE_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "ioDiskWriteRate")
            .withDescription("current disk write rate in bytes per second")
            .withFormat(FORMAT_16_0)
            .withUnit("bytes/s")
            .withHalfLife(0.0);
    private final RunningAverageMetric diskWriteRate;

    private final IOStatsReader ioStatsReader;

    // High-frequency sampling for accurate rate calculation
    private static final long SAMPLE_INTERVAL_MS = 100; // Sample every 100ms

    // For calculating disk I/O rates (matching iotop behavior)
    private long lastDiskBytesRead = 0;
    private long lastDiskBytesWritten = 0;
    private long lastSampleTime = 0;

    // Accumulated values since last metrics update
    private long accumulatedReadRate = 0;
    private long accumulatedWriteRate = 0;
    private int sampleCount = 0;

    private static final AtomicBoolean SETUP_STARTED = new AtomicBoolean();

    /**
     * Setup all metrics related to I/O statistics
     *
     * @param metrics a reference to the metrics-system
     */
    public static void setup(final Metrics metrics) {
        if (SETUP_STARTED.compareAndSet(false, true)) {
            final IOMetrics ioMetrics = new IOMetrics(metrics);
            metrics.addUpdater(ioMetrics::update);
        }
    }

    /**
     * Private constructor. Use {@link #setup(Metrics)} to initialize.
     *
     * @param metrics the metrics system to register with
     * @throws NullPointerException if {@code metrics} parameter is {@code null}
     */
    private IOMetrics(final Metrics metrics) {
        Objects.requireNonNull(metrics, "metrics must not be null");

        this.ioStatsReader = IOStatsFactory.create();

        // Register disk I/O metrics only
        diskBytesRead = metrics.getOrCreate(DISK_BYTES_READ_CONFIG);
        diskBytesWritten = metrics.getOrCreate(DISK_BYTES_WRITTEN_CONFIG);
        cancelledWriteBytes = metrics.getOrCreate(CANCELLED_WRITE_BYTES_CONFIG);
        diskReadRate = metrics.getOrCreate(DISK_READ_RATE_CONFIG);
        diskWriteRate = metrics.getOrCreate(DISK_WRITE_RATE_CONFIG);
    }

    /**
     * Calculate delta between two counter values, handling potential wraparound.
     * This matches iotop's rrv() function for round-robin value calculation.
     *
     * @param current the current counter value
     * @param previous the previous counter value
     * @return the delta, accounting for potential counter wraparound
     */
    private static long calculateDelta(final long current, final long previous) {
        if (current < previous) {
            // Counter wrapped around - calculate wraparound delta
            // For 64-bit: (MAX_VALUE - previous) + current + 1
            return (Long.MAX_VALUE - previous) + current + 1;
        } else {
            // Normal case - simple subtraction
            return current - previous;
        }
    }

    /**
     * Sample disk I/O at high frequency and accumulate for accurate rate calculation.
     * This is called internally at SAMPLE_INTERVAL_MS intervals.
     */
    private void sampleIOStats() {
        final IOStatsReader.IOStats stats = ioStatsReader.readIOStats();
        final long currentTime = System.currentTimeMillis();

        // Calculate rate since last sample
        if (lastSampleTime > 0) {
            final long deltaTime = currentTime - lastSampleTime;
            if (deltaTime > 0) {
                // Calculate delta in disk bytes with wraparound protection
                final long deltaRead = calculateDelta(stats.diskBytesRead(), lastDiskBytesRead);
                final long deltaWrite = calculateDelta(stats.diskBytesWritten(), lastDiskBytesWritten);

                // Convert to bytes per second: (bytes * 1000ms/s) / milliseconds
                final long currentReadRate = (deltaRead * 1000) / deltaTime;
                final long currentWriteRate = (deltaWrite * 1000) / deltaTime;

                // Accumulate rates for averaging
                accumulatedReadRate += currentReadRate;
                accumulatedWriteRate += currentWriteRate;
                sampleCount++;
            }
        }

        // Update tracking values for next sample
        lastDiskBytesRead = stats.diskBytesRead();
        lastDiskBytesWritten = stats.diskBytesWritten();
        lastSampleTime = currentTime;
    }

    /**
     * Update all disk I/O metrics with current values from the operating system.
     * This method is called periodically by the metrics system (typically once per second).
     * It reports the average rate calculated from multiple high-frequency samples.
     */
    private void update() {
        if (!ioStatsReader.isSupported()) {
            return;
        }

        // Sample at high frequency (100ms) until it's time to report
        final long currentTime = System.currentTimeMillis();
        if (lastSampleTime == 0 || (currentTime - lastSampleTime) >= SAMPLE_INTERVAL_MS) {
            sampleIOStats();
        }

        // Read current stats for cumulative metrics
        final IOStatsReader.IOStats stats = ioStatsReader.readIOStats();

        // Update cumulative disk I/O metrics (always)
        diskBytesRead.update(stats.diskBytesRead());
        diskBytesWritten.update(stats.diskBytesWritten());
        cancelledWriteBytes.update(stats.cancelledWriteBytes());

        // Report average rate from accumulated samples
        if (sampleCount > 0) {
            final long avgReadRate = accumulatedReadRate / sampleCount;
            final long avgWriteRate = accumulatedWriteRate / sampleCount;

            diskReadRate.update(avgReadRate);
            diskWriteRate.update(avgWriteRate);

            // Reset accumulators for next reporting period
            accumulatedReadRate = 0;
            accumulatedWriteRate = 0;
            sampleCount = 0;
        }
    }
}
