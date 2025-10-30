// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.io;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * No-op implementation of IOStatsReader for platforms that don't support I/O statistics.
 * <p>
 * This implementation always returns empty stats and reports as unsupported.
 */
public final class NoOpIOStats implements IOStatsReader {

    /**
     * Creates a new NoOpIOStats instance.
     */
    public NoOpIOStats() {
        // No initialization needed
    }

    /**
     * Always returns empty I/O statistics.
     *
     * @return empty IOStats
     */
    @Override
    @NonNull
    public IOStats readIOStats() {
        return IOStats.EMPTY;
    }

    /**
     * Always returns false since this platform doesn't support I/O stats.
     *
     * @return false
     */
    @Override
    public boolean isSupported() {
        return false;
    }
}
