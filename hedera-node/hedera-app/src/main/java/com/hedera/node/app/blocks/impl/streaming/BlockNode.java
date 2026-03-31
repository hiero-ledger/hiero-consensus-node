// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BlockNode {

    private static final Logger logger = LogManager.getLogger(BlockNode.class);

    private static final int MAX_HISTORY_ENTRIES = 10;

    private BlockNodeConfiguration configuration;
    private Instant nodeCoolDownTimestamp;
    private final Map<ConnectionId, BlockNodeConnectionHistory> connectionHistories = new HashMap<>();
    private final AtomicReference<BlockNodeStreamingConnection> activeStreamingConnectionRef = new AtomicReference<>();
    private boolean isTerminated = false;
    private final AtomicInteger globalActiveStreamingConnectionCount;
    private final AtomicInteger localActiveStreamingConnectionCount = new AtomicInteger(0);
    private final BlockNodeStats stats;

    public BlockNode(
            @NonNull final BlockNodeConfiguration configuration,
            @NonNull final AtomicInteger globalActiveStreamingConnectionCount,
            @NonNull final BlockNodeStats stats) {
        this.configuration = requireNonNull(configuration, "Configuration is required");
        this.globalActiveStreamingConnectionCount = requireNonNull(
                globalActiveStreamingConnectionCount, "Global active streaming connection counter is required");
        this.stats = requireNonNull(stats, "Block node stats is required");
    }

    @NonNull
    BlockNodeStats stats() {
        return stats;
    }

    @NonNull
    BlockNodeConfiguration configuration() {
        return configuration;
    }

    @NonNull
    Map<ConnectionId, BlockNodeConnectionHistory> connectionHistory() {
        return connectionHistories;
    }

    boolean isStreamingCandidate() {
        return !isTerminated && !isNodeInCoolDown() && activeStreamingConnectionRef.get() == null;
    }

    void onConfigUpdate(@NonNull final BlockNodeConfiguration configuration) {
        this.configuration = requireNonNull(configuration, "Configuration is required");
        nodeCoolDownTimestamp = null;

        final BlockNodeStreamingConnection activeConnection = activeStreamingConnectionRef.get();
        if (activeConnection != null) {
            activeConnection.closeAtBlockBoundary(CloseReason.CONFIG_UPDATE);
        }
    }

    boolean isRemovable() {
        return isTerminated && activeStreamingConnectionRef.get() == null;
    }

    void closeConnection(@NonNull final CloseReason closeReason) {
        final BlockNodeStreamingConnection activeConnection = activeStreamingConnectionRef.get();
        if (activeConnection != null) {
            activeConnection.closeAtBlockBoundary(closeReason);
        }
    }

    void onTerminate(final CloseReason closeReason) {
        isTerminated = true;
        closeConnection(closeReason);
    }

    boolean isNodeInCoolDown() {
        return nodeCoolDownTimestamp != null && nodeCoolDownTimestamp.isAfter(Instant.now());
    }

    boolean onActive(@NonNull final BlockNodeStreamingConnection connection) {
        if (!activeStreamingConnectionRef.compareAndSet(null, connection)) {
            logger.warn(
                    "Attempted to open multiple streaming connections to the same block node (address: {})",
                    configuration.address());
            return false;
        }

        final BlockNodeConnectionHistory connectionHistory = new BlockNodeConnectionHistory(connection);
        connectionHistories.put(connection.connectionId(), connectionHistory);

        connectionHistory.onActive(connection);
        globalActiveStreamingConnectionCount.incrementAndGet();
        localActiveStreamingConnectionCount.incrementAndGet();

        if (connectionHistories.size() > MAX_HISTORY_ENTRIES) {
            // prune the oldest entry from the connection history
            BlockNodeConnectionHistory oldestEntry =
                    connectionHistories.values().stream().findAny().get();
            for (final BlockNodeConnectionHistory entry : connectionHistories.values()) {
                if (entry.createTimestamp.isBefore(oldestEntry.createTimestamp)) {
                    oldestEntry = entry;
                }
            }

            logger.trace(
                    "Connection (id: {}) removed from node (address: {}) history",
                    oldestEntry.connectionId,
                    configuration.address());
        }

        return true;
    }

    void onClose(@NonNull final BlockNodeStreamingConnection connection) {
        activeStreamingConnectionRef.compareAndSet(connection, null);

        final BlockNodeConnectionHistory connectionInfo = connectionHistories.get(connection.connectionId());
        if (connectionInfo == null) {
            logger.warn(
                    "Connection (address: {}, id: {}) was not tracked on connect",
                    connection.configuration().address(),
                    connection.connectionId());
            return;
        }

        connectionInfo.onClose(connection);
        globalActiveStreamingConnectionCount.decrementAndGet();
        localActiveStreamingConnectionCount.decrementAndGet();

        final CloseReason closeReason = connection.closeReason();
        if (closeReason == null) {
            logger.warn(
                    "Connection (address: {}, id: {}) was closed without a close reason",
                    connection.configuration().address(),
                    connection.connectionId());
        } else if (closeReason.isCoolDownRequired()) {
            // the close reason indicates that likely a non-transient issue was encountered, thus mark this node
            // as being in a cool down period so don't immediately try to go back to it
            nodeCoolDownTimestamp = Instant.now().plusSeconds(30);
        }
    }

    static class BlockNodeConnectionHistory {
        private final ConnectionId connectionId;
        private final Instant createTimestamp;
        private Instant activeTimestamp;
        private Instant closeTimestamp;
        private CloseReason closeReason;
        private int numBlocksSent;

        BlockNodeConnectionHistory(@NonNull final BlockNodeStreamingConnection connection) {
            requireNonNull(connection, "Connection is required");
            connectionId = connection.connectionId();
            createTimestamp = connection.createTimestamp();
        }

        @NonNull
        ConnectionId connectionId() {
            return connectionId;
        }

        @NonNull
        Instant createTimestamp() {
            return createTimestamp;
        }

        @Nullable
        Instant activeTimestamp() {
            return activeTimestamp;
        }

        @Nullable
        Instant closeTimestamp() {
            return closeTimestamp;
        }

        @Nullable
        CloseReason closeReason() {
            return closeReason;
        }

        int numBlocksSent() {
            return numBlocksSent;
        }

        void onActive(@NonNull final BlockNodeStreamingConnection connection) {
            requireNonNull(connection, "Connection is required");
            activeTimestamp = connection.activeTimestamp();
        }

        void onClose(@NonNull final BlockNodeStreamingConnection connection) {
            requireNonNull(connection, "Connection is required");
            closeTimestamp = connection.closeTimestamp();
            closeReason = connection.closeReason();
            numBlocksSent = connection.numberOfBlocksSent();
        }

        @Nullable
        Duration duration() {
            if (activeTimestamp == null || closeTimestamp == null) {
                return null;
            }

            return Duration.between(activeTimestamp, closeTimestamp);
        }
    }
}
