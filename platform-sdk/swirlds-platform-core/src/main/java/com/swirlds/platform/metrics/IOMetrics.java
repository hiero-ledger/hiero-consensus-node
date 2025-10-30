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
 * Collection of metrics related to I/O statistics from the operating system.
 * <p>
 * This class tracks process-level I/O metrics including:
 * <ul>
 *     <li>Total bytes read and written (including cached)</li>
 *     <li>Actual disk bytes read and written</li>
 *     <li>Read and write system call counts</li>
 * </ul>
 * <p>
 * These metrics are collected from the /proc filesystem on Linux systems.
 * On other platforms, the metrics will be registered but will remain at zero.
 */
public final class IOMetrics {

    private static final RunningAverageMetric.Config BYTES_READ_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "ioProcessBytesRead")
            .withDescription("total bytes read by process (including cached reads)")
            .withFormat(FORMAT_16_0)
            .withUnit("bytes")
            .withHalfLife(0.0);
    private final RunningAverageMetric bytesRead;

    private static final RunningAverageMetric.Config BYTES_WRITTEN_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "ioProcessBytesWritten")
            .withDescription("total bytes written by process (including cached writes)")
            .withFormat(FORMAT_16_0)
            .withUnit("bytes")
            .withHalfLife(0.0);
    private final RunningAverageMetric bytesWritten;

    private static final RunningAverageMetric.Config DISK_BYTES_READ_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "ioDiskBytesRead")
            .withDescription("bytes actually read from storage device")
            .withFormat(FORMAT_16_0)
            .withUnit("bytes")
            .withHalfLife(0.0);
    private final RunningAverageMetric diskBytesRead;

    private static final RunningAverageMetric.Config DISK_BYTES_WRITTEN_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "ioDiskBytesWritten")
            .withDescription("bytes actually written to storage device")
            .withFormat(FORMAT_16_0)
            .withUnit("bytes")
            .withHalfLife(0.0);
    private final RunningAverageMetric diskBytesWritten;

    private static final RunningAverageMetric.Config READ_SYSCALLS_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "ioReadSyscalls")
            .withDescription("number of read system calls")
            .withFormat(FORMAT_16_0)
            .withUnit("syscalls")
            .withHalfLife(0.0);
    private final RunningAverageMetric readSyscalls;

    private static final RunningAverageMetric.Config WRITE_SYSCALLS_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "ioWriteSyscalls")
            .withDescription("number of write system calls")
            .withFormat(FORMAT_16_0)
            .withUnit("syscalls")
            .withHalfLife(0.0);
    private final RunningAverageMetric writeSyscalls;

    private final IOStatsReader ioStatsReader;

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

        // Register all metrics
        bytesRead = metrics.getOrCreate(BYTES_READ_CONFIG);
        bytesWritten = metrics.getOrCreate(BYTES_WRITTEN_CONFIG);
        diskBytesRead = metrics.getOrCreate(DISK_BYTES_READ_CONFIG);
        diskBytesWritten = metrics.getOrCreate(DISK_BYTES_WRITTEN_CONFIG);
        readSyscalls = metrics.getOrCreate(READ_SYSCALLS_CONFIG);
        writeSyscalls = metrics.getOrCreate(WRITE_SYSCALLS_CONFIG);
    }

    /**
     * Update all I/O metrics with current values from the operating system.
     * This method is called periodically by the metrics system.
     */
    private void update() {
        if (!ioStatsReader.isSupported()) {
            return;
        }

        final IOStatsReader.IOStats stats = ioStatsReader.readIOStats();

        bytesRead.update(stats.bytesRead());
        bytesWritten.update(stats.bytesWritten());
        diskBytesRead.update(stats.diskBytesRead());
        diskBytesWritten.update(stats.diskBytesWritten());
        readSyscalls.update(stats.readSyscalls());
        writeSyscalls.update(stats.writeSyscalls());
    }
}
