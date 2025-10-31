// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.io;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Interface for reading I/O statistics from the operating system.
 * <p>
 * Different implementations exist for different operating systems (Linux, macOS, etc).
 */
public interface IOStatsReader {
    /**
     * Reads the current I/O statistics for the process.
     *
     * @return IOStats record containing the current I/O metrics
     */
    @NonNull
    IOStats readIOStats();

    /**
     * Returns whether this platform supports I/O statistics collection.
     *
     * @return true if I/O stats are available, false otherwise
     */
    boolean isSupported();

    /**
     * Record containing I/O statistics for a process.
     *
     * @param bytesRead total bytes read (including cache)
     * @param bytesWritten total bytes written (including cache)
     * @param diskBytesRead bytes actually read from storage device
     * @param diskBytesWritten bytes actually written to storage device
     * @param readSyscalls number of read system calls or block reads
     * @param writeSyscalls number of write system calls or block writes
     * @param cancelledWriteBytes bytes scheduled for write but cancelled (Linux-specific)
     */
    record IOStats(
            long bytesRead,
            long bytesWritten,
            long diskBytesRead,
            long diskBytesWritten,
            long readSyscalls,
            long writeSyscalls,
            long cancelledWriteBytes) {

        /**
         * Empty I/O stats (all zeros) used when stats are unavailable.
         */
        public static final IOStats EMPTY = new IOStats(0L, 0L, 0L, 0L, 0L, 0L, 0L);

        /**
         * Checks if these stats are empty (all zeros).
         *
         * @return true if all values are zero, false otherwise
         */
        public boolean isEmpty() {
            return bytesRead == 0
                    && bytesWritten == 0
                    && diskBytesRead == 0
                    && diskBytesWritten == 0
                    && readSyscalls == 0
                    && writeSyscalls == 0
                    && cancelledWriteBytes == 0;
        }
    }
}
