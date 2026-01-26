// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol.rpc;

import com.swirlds.base.time.Time;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.metrics.SyncMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Consumer;
import org.hiero.consensus.model.node.NodeId;

/**
 * Helper class for checking if rpc communication is overloaded (ping or output queue) and informs to disable broadcast
 * if it is the case. From this class point of view it doesn't know anything about broadcast, just informs provided
 * callback about being 'overloaded'
 * Please see {@link SyncConfig.throttleOutputQueueThreshold}, {@link SyncConfig.disableBroadcastPingThreshold} and
 * {@link SyncConfig.pauseBroadcastOnLag} for configuration options
 */
public class RpcOverloadMonitor {

    private final NodeId peerId;
    private final SyncConfig syncConfig;
    private final SyncMetrics syncMetrics;
    private final Time time;
    private final Consumer<Boolean> communicationOverloadHandler;

    private volatile long lastCommunicationOverloadReported = -1;
    private volatile long disabledBroadcastDueToLag = -1;

    public RpcOverloadMonitor(
            @NonNull final NodeId peerId,
            @NonNull final SyncConfig syncConfig,
            @NonNull final SyncMetrics syncMetrics,
            @NonNull final Time time,
            @NonNull final Consumer<Boolean> communicationOverloadHandler) {

        this.peerId = Objects.requireNonNull(peerId);
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
    public void reportOutputQueueSize(final int size) {
        if (size > syncConfig.throttleOutputQueueThreshold()) {
            communicationOverloadHandler.accept(true);
            syncMetrics.disabledBroadcastDueToOverload(true);
            lastCommunicationOverloadReported = time.currentTimeMillis();
        } else {
            if (time.currentTimeMillis() - lastCommunicationOverloadReported
                    > syncConfig.pauseBroadcastOnLag().toMillis()) {
                syncMetrics.disabledBroadcastDueToOverload(false);
                communicationOverloadHandler.accept(false);
            }
        }
    }

    /**
     * Check if given ping of RPC is not an indication of overloaded communication
     *
     * @param pingMillis roundtrip of message being interpreted in milliseconds
     */
    public void reportPing(final long pingMillis) {
        if (disabledBroadcastDueToLag < 0
                && pingMillis > syncConfig.disableBroadcastPingThreshold().toMillis()) {
            disabledBroadcastDueToLag = time.currentTimeMillis();
            syncMetrics.disabledBroadcastDueToLag(true);
            communicationOverloadHandler.accept(true);
        } else if (disabledBroadcastDueToLag > 0
                && pingMillis < syncConfig.disableBroadcastPingThreshold().toMillis()) {
            if (time.currentTimeMillis() - disabledBroadcastDueToLag
                    > syncConfig.pauseBroadcastOnLag().toMillis()) {
                disabledBroadcastDueToLag = -1;
                syncMetrics.disabledBroadcastDueToLag(false);
                communicationOverloadHandler.accept(false);
            }
        }
    }
}
