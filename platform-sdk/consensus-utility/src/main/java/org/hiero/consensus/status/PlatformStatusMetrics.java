// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status;

import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.metrics.statistics.StatConstructor;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Encapsulates metrics for the platform status.
 */
public class PlatformStatusMetrics {

    private final AtomicReference<PlatformStatus> currentStatus = new AtomicReference<>(PlatformStatus.STARTING_UP);

    /**
     * Constructor
     *
     * @param metrics the metrics
     */
    public PlatformStatusMetrics(@NonNull final Metrics metrics) {
        metrics.getOrCreate(StatConstructor.createEnumStat(
                "PlatformStatus", Metrics.PLATFORM_CATEGORY, PlatformStatus.values(), currentStatus::get));
    }

    /**
     * Set the current status.
     *
     * @param status the new status
     */
    public void setCurrentStatus(@NonNull final PlatformStatus status) {
        currentStatus.set(status);
    }
}
