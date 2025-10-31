// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.io;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reads I/O statistics from the Linux /proc filesystem for the current process.
 * <p>
 * This class parses {@code /proc/[pid]/io} to extract various I/O metrics including:
 * <ul>
 *     <li>rchar - bytes read (including cached reads)</li>
 *     <li>wchar - bytes written (including cached writes)</li>
 *     <li>read_bytes - actual bytes read from storage</li>
 *     <li>write_bytes - actual bytes written to storage</li>
 *     <li>syscr - number of read syscalls</li>
 *     <li>syscw - number of write syscalls</li>
 *     <li>cancelled_write_bytes - bytes scheduled for write but cancelled</li>
 * </ul>
 * <p>
 * This implementation is Linux-specific and will return empty stats on other operating systems.
 */
public final class ProcIOStats implements IOStatsReader {
    private static final Logger logger = LogManager.getLogger(ProcIOStats.class);

    private final long pid;
    private final Path ioPath;
    private final boolean isSupported;

    /**
     * Creates a new ProcIOStats reader for the current process.
     */
    public ProcIOStats() {
        this.pid = ProcessHandle.current().pid();
        this.ioPath = Path.of("/proc", String.valueOf(pid), "io");
        this.isSupported = checkSupported();

        if (!isSupported) {
            logger.info("Process I/O stats not supported on this platform (expected on non-Linux systems)");
        }
    }

    /**
     * Check if /proc filesystem I/O stats are available on this system.
     *
     * @return true if supported, false otherwise
     */
    private boolean checkSupported() {
        try {
            return Files.exists(ioPath) && Files.isReadable(ioPath);
        } catch (final SecurityException e) {
            logger.warn("Security exception checking for /proc I/O stats", e);
            return false;
        }
    }

    /**
     * Reads the current I/O statistics for the process.
     *
     * @return IOStats record containing the current I/O metrics, or empty stats if unavailable
     */
    @Override
    @NonNull
    public IOStats readIOStats() {
        if (!isSupported) {
            return IOStats.EMPTY;
        }

        try {
            final Map<String, Long> stats = new HashMap<>();
            Files.readAllLines(ioPath).forEach(line -> {
                final String[] parts = line.split(":\\s+");
                if (parts.length == 2) {
                    try {
                        stats.put(parts[0], Long.parseLong(parts[1]));
                    } catch (final NumberFormatException e) {
                        logger.debug("Failed to parse I/O stat line: {}", line, e);
                    }
                }
            });

            return new IOStats(
                    stats.getOrDefault("rchar", 0L),
                    stats.getOrDefault("wchar", 0L),
                    stats.getOrDefault("read_bytes", 0L),
                    stats.getOrDefault("write_bytes", 0L),
                    stats.getOrDefault("syscr", 0L),
                    stats.getOrDefault("syscw", 0L),
                    stats.getOrDefault("cancelled_write_bytes", 0L));
        } catch (final NoSuchFileException e) {
            logger.warn("Process I/O stats file disappeared (pid={}): {}", pid, e.getMessage());
            return IOStats.EMPTY;
        } catch (final IOException e) {
            logger.error("Failed to read process I/O stats", e);
            return IOStats.EMPTY;
        }
    }

    /**
     * Returns whether this platform supports I/O statistics collection.
     *
     * @return true if I/O stats are available, false otherwise
     */
    @Override
    public boolean isSupported() {
        return isSupported;
    }
}
