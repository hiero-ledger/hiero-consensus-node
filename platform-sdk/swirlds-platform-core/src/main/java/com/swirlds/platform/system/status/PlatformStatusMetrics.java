// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.status;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.stats.StatConstructor;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Encapsulates metrics for the platform status.
 */
public class PlatformStatusMetrics {

    private final AtomicReference<PlatformStatus> currentStatus = new AtomicReference<>(PlatformStatus.STARTING_UP);

    /**
     * Constructor
     *
     * @param platformContext the platform context
     */
    public PlatformStatusMetrics(@NonNull final PlatformContext platformContext) {
        platformContext
                .getMetrics()
                .getOrCreate(StatConstructor.createEnumStat(
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
