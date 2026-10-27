// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.IntSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.consensus.metrics.FunctionGauge;

/**
 * Metrics for the CLPR sync orchestrator, so operators can alert on the rate of new Channels and on sync-slot
 * saturation, the two signals of a Channel-spam or sync-starvation attack.
 */
@Singleton
public class ClprSyncMetrics {

    private static final String CATEGORY = "clpr";

    @Nullable
    private final Metrics metrics;

    @Nullable
    private final Counter channelsActivated;

    @Nullable
    private final Counter syncsQueued;

    @Nullable
    private final Counter syncFailures;

    /**
     * Creates the metrics, registering them with {@code metrics}.
     *
     * @param metrics the metrics system
     */
    @Inject
    public ClprSyncMetrics(@NonNull final Metrics metrics) {
        this.metrics = requireNonNull(metrics);
        this.channelsActivated = metrics.getOrCreate(new Counter.Config(CATEGORY, "channelsActivated")
                .withDescription("CLPR Channels activated (completed) on this node"));
        this.syncsQueued = metrics.getOrCreate(new Counter.Config(CATEGORY, "syncsQueued")
                .withDescription("CLPR outbound syncs that had to wait because every sync slot was busy"));
        this.syncFailures = metrics.getOrCreate(new Counter.Config(CATEGORY, "syncFailures")
                .withDescription("CLPR outbound syncs that failed, timed out, or hit a protocol violation"));
    }

    private ClprSyncMetrics() {
        this.metrics = null;
        this.channelsActivated = null;
        this.syncsQueued = null;
        this.syncFailures = null;
    }

    /**
     * Returns metrics that record nothing.
     *
     * @return no-op metrics
     */
    @NonNull
    public static ClprSyncMetrics noop() {
        return new ClprSyncMetrics();
    }

    /**
     * Registers gauges over the orchestrator's sync slots.
     *
     * @param busySlots the number of sync slots in use
     * @param waitingSyncs the number of Channels waiting for a slot
     */
    void registerSlotGauges(@NonNull final IntSupplier busySlots, @NonNull final IntSupplier waitingSyncs) {
        if (metrics == null) {
            return;
        }
        metrics.getOrCreate(new FunctionGauge.Config<>(CATEGORY, "syncSlotsBusy", Integer.class, busySlots::getAsInt)
                .withDescription("CLPR outbound sync slots in use"));
        metrics.getOrCreate(new FunctionGauge.Config<>(CATEGORY, "syncsWaiting", Integer.class, waitingSyncs::getAsInt)
                .withDescription("CLPR Channels waiting for an outbound sync slot"));
    }

    void channelActivated() {
        if (channelsActivated != null) {
            channelsActivated.increment();
        }
    }

    void syncQueued() {
        if (syncsQueued != null) {
            syncsQueued.increment();
        }
    }

    void syncFailed() {
        if (syncFailures != null) {
            syncFailures.increment();
        }
    }
}
