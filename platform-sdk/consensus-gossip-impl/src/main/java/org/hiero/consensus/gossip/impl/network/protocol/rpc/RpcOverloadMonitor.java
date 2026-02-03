// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.protocol.rpc;

import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Consumer;
import org.hiero.consensus.gossip.config.SyncConfig;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncMetrics;

/**
 * Helper class for checking if rpc communication is overloaded (ping or output queue) and informs to disable broadcast
 * if it is the case. From this class point of view it doesn't know anything about broadcast, just informs provided
 * callback about being 'overloaded' Please see {@link SyncConfig#throttleOutputQueueThreshold()},
 * {@link SyncConfig#disableBroadcastPingThreshold()} and {@link SyncConfig#pauseBroadcastOnLag()} for configuration
 * options
 */
public class RpcOverloadMonitor {

    private static final long NOT_DISABLED = -1L;

    private final SyncConfig syncConfig;
    private final SyncMetrics syncMetrics;
    private final Time time;
    private final Consumer<Boolean> communicationOverloadHandler;

    private volatile long disabledBroadcastDueToQueueSizeTime = NOT_DISABLED;
    private volatile long disabledBroadcastDueToLagTime = NOT_DISABLED;

    RpcOverloadMonitor(
            @NonNull final SyncConfig syncConfig,
            @NonNull final SyncMetrics syncMetrics,
            @NonNull final Time time,
            @NonNull final Consumer<Boolean> communicationOverloadHandler) {

        this.syncConfig = Objects.requireNonNull(syncConfig);
        this.syncMetrics = Objects.requireNonNull(syncMetrics);
        this.time = Objects.requireNonNull(time);
        this.communicationOverloadHandler = Objects.requireNonNull(communicationOverloadHandler);
    }

    /**
     * Check if given output queue size for RPC is not an indication of overloaded communication
     *
     * @param size number of items in the queue
     */
    void reportOutputQueueSize(final int size) {
        if (size > syncConfig.throttleOutputQueueThreshold()) {
            if (disabledBroadcastDueToQueueSizeTime == NOT_DISABLED) {
                syncMetrics.disabledBroadcastDueToOverload(true);
                // it is ok to call it multiple times
                communicationOverloadHandler.accept(true);
            }
            // we always need to update last time when queue size was breached
            disabledBroadcastDueToQueueSizeTime = time.currentTimeMillis();
        } else if (disabledBroadcastDueToQueueSizeTime != NOT_DISABLED
                && enoughTimePassedAfterDisable(disabledBroadcastDueToQueueSizeTime)) {
            disabledBroadcastDueToQueueSizeTime = NOT_DISABLED;
            syncMetrics.disabledBroadcastDueToOverload(false);
            if (disabledBroadcastDueToLagTime == NOT_DISABLED) {
                // if both are not disabled, notify the client
                communicationOverloadHandler.accept(false);
            }
        }
    }

    /**
     * Check if the RPC ping value indicates overloaded communication
     *
     * @param pingMillis roundtrip of message being interpreted in milliseconds
     */
    void reportPing(final long pingMillis) {
        if (pingMillis > syncConfig.disableBroadcastPingThreshold().toMillis()) {
            if (disabledBroadcastDueToLagTime == NOT_DISABLED) {
                syncMetrics.disabledBroadcastDueToLag(true);
                // it is ok to call it multiple times
                communicationOverloadHandler.accept(true);
            }
            // we always need to update last time when ping was reported as broken
            disabledBroadcastDueToLagTime = time.currentTimeMillis();
        } else if (disabledBroadcastDueToLagTime != NOT_DISABLED
                && enoughTimePassedAfterDisable(disabledBroadcastDueToLagTime)) {
            disabledBroadcastDueToLagTime = NOT_DISABLED;
            syncMetrics.disabledBroadcastDueToLag(false);
            if (disabledBroadcastDueToQueueSizeTime == NOT_DISABLED) {
                // if both are not disabled, notify the client
                communicationOverloadHandler.accept(false);
            }
        }
    }

    /**
     * Each time we disable broadcast due to some factor, we don't want to enable it back until that factor is ok for
     * certain period of time, to avoid flickering
     *
     * @param disableTime when the metric breach was observed last time
     * @return has enough time passed since last breach to be able to enable broadcast back
     */
    private boolean enoughTimePassedAfterDisable(final long disableTime) {
        return time.currentTimeMillis() - disableTime
                > syncConfig.pauseBroadcastOnLag().toMillis();
    }
}
