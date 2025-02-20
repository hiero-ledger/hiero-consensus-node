// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import com.swirlds.common.metrics.statistics.StatsBuffered;
import com.swirlds.metrics.api.snapshot.SnapshotableMetric;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * This class is only used to simplify the migration and will be removed afterwards.
 *
 * @deprecated This class is only temporary and will be removed during the Metric overhaul.
 */
@Deprecated(forRemoval = true)
public interface PlatformMetric extends SnapshotableMetric {

    /**
     * This method returns the {@link StatsBuffered} of this metric, if there is one.
     * <p>
     * This method is only used to simplify the migration and will be removed afterwards
     *
     * @return the {@code StatsBuffered}, if there is one, {@code null} otherwise
     * @deprecated This method is only temporary and will be removed during the Metric overhaul.
     */
    @Nullable
    default StatsBuffered getStatsBuffered() {
        return null;
    }
}
