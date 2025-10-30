// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.io;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Factory for creating the appropriate I/O stats reader for the current platform.
 * <p>
 * This factory detects the operating system and returns the best available
 * implementation for collecting I/O statistics.
 */
public final class IOStatsFactory {
    private static final Logger logger = LogManager.getLogger(IOStatsFactory.class);

    private IOStatsFactory() {
        // Utility class, no instantiation
    }

    /**
     * Creates an I/O stats reader appropriate for the current operating system.
     * <p>
     * The factory chooses the implementation based on OS detection:
     * <ul>
     *     <li>Linux: Uses /proc filesystem (most detailed metrics)</li>
     *     <li>macOS: Future implementation will use getrusage() or libproc</li>
     *     <li>Other: Returns no-op implementation</li>
     * </ul>
     *
     * @return IOStatsReader implementation suitable for this platform
     */
    @NonNull
    public static IOStatsReader create() {
        final String osName = System.getProperty("os.name", "unknown").toLowerCase();

        logger.info("Detecting I/O stats implementation for OS: {}", osName);

        if (osName.contains("linux")) {
            logger.info("Using ProcIOStats for Linux platform");
            return new ProcIOStats();
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            // TODO: Implement macOS-specific stats using getrusage() or libproc
            logger.info("macOS detected, but macOS I/O stats not yet implemented - using no-op");
            return new NoOpIOStats();
        } else if (osName.contains("windows")) {
            // TODO: Implement Windows-specific stats using Performance Counters
            logger.info("Windows detected, but Windows I/O stats not yet implemented - using no-op");
            return new NoOpIOStats();
        } else {
            logger.info("Unknown OS '{}', using no-op I/O stats", osName);
            return new NoOpIOStats();
        }
    }

    /**
     * Creates an I/O stats reader for testing purposes, allowing override of OS detection.
     *
     * @param osName the operating system name to use for selection
     * @return IOStatsReader implementation suitable for the specified platform
     */
    @NonNull
    static IOStatsReader createForOS(@NonNull final String osName) {
        final String lowerOsName = osName.toLowerCase();

        if (lowerOsName.contains("linux")) {
            return new ProcIOStats();
        } else if (lowerOsName.contains("mac") || lowerOsName.contains("darwin")) {
            return new NoOpIOStats(); // TODO: Return MacOSIOStats when implemented
        } else {
            return new NoOpIOStats();
        }
    }
}
