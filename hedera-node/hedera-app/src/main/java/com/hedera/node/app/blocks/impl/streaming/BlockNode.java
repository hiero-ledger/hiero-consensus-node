// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeEndpoint;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Represents a single block node that can be used to send blocks to.
 */
public class BlockNode {

    private static final Logger logger = LogManager.getLogger(BlockNode.class);

    /**
     * Maximum number of connection history events to keep track of.
     */
    private static final int MAX_HISTORY_ENTRIES = 5;
    /**
     * Mechanism to access application configurations.
     */
    private final ConfigProvider configProvider;
    /**
     * The latest configuration associated with this block node.
     */
    private final AtomicReference<BlockNodeConfiguration> configurationRef = new AtomicReference<>();
    /**
     * Timestamp of when this block node exits cool down and is eligible for streaming to again.
     */
    private final AtomicReference<Instant> nodeCoolDownTimestampRef = new AtomicReference<>();
    /**
     * Set of the most recent connections associated with this block node. This is for debugging purposes.
     */
    private final ConcurrentMap<ConnectionId, ConnectionHistory> connectionHistories = new ConcurrentHashMap<>();
    /**
     * If this block node is actively streaming, the connection will be referenced here.
     */
    private final AtomicReference<BlockNodeStreamingConnection> activeStreamingConnectionRef = new AtomicReference<>();
    /**
     * Flag to indicate if this block node is "terminal" and thus is not eligible for streaming to. A node is in this
     * terminal state usually because it was removed from the block node configuration set and isn't a node we want
     * to stream to anymore.
     */
    private final AtomicBoolean isTerminated = new AtomicBoolean(false);
    /**
     * Reference to the global counter for tracking the number of active streaming connections from this consensus node.
     */
    private final AtomicInteger globalActiveStreamingConnectionCount;
    /**
     * Counter to track the number of local connections active for this block node. Typically, this value will always be
     * 0 or 1, but there are cases where more connections may be active. For example, if this block node is the only
     * available node to stream to and the previous active connection reached its periodic/auto reset time then the old
     * connection will be closed while a new connection is established. If the old connection was closed at a block
     * boundary, it may take a little time for it to close, and thus two connections will be active.
     */
    private final AtomicInteger localActiveStreamingConnectionCount = new AtomicInteger(0);
    /**
     * Reference to statistics related to this block node.
     */
    private final BlockNodeStats stats;
    /**
     * Reference to the clock used any time-based operations. This is used for testing for better time control.
     */
    private final Clock clock;

    /**
     * Creates a new block node instance.
     *
     * @param configProvider mechanism to access application config
     * @param configuration the configuration associated with this block node
     * @param globalActiveStreamingConnectionCount the global active connection counter
     * @param stats the block node stats for this block node
     */
    public BlockNode(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockNodeConfiguration configuration,
            @NonNull final AtomicInteger globalActiveStreamingConnectionCount,
            @NonNull final BlockNodeStats stats) {
        this(configProvider, configuration, globalActiveStreamingConnectionCount, stats, Clock.systemUTC());
    }

    /**
     * This constructor is for testing purposes only.
     */
    BlockNode(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockNodeConfiguration configuration,
            @NonNull final AtomicInteger globalActiveStreamingConnectionCount,
            @NonNull final BlockNodeStats stats,
            @NonNull final Clock clock) {
        this.configProvider = requireNonNull(configProvider, "Configuration provider is required");
        this.configurationRef.set(requireNonNull(configuration, "Configuration is required"));
        this.globalActiveStreamingConnectionCount = requireNonNull(
                globalActiveStreamingConnectionCount, "Global active streaming connection counter is required");
        this.stats = requireNonNull(stats, "Block node stats is required");
        this.clock = requireNonNull(clock, "Clock is required");
    }

    /**
     * Writes information about this block node to the specified StringBuilder. Information such as basic configuration,
     * whether this node is a streaming candidate, and a history of the most recent connections will be included.
     *
     * @param sb the StringBuilder instance to append the information to
     */
    void writeInformation(@NonNull final StringBuilder sb) {
        requireNonNull(sb, "StringBuilder is required");
        final BlockNodeConfiguration configuration = configurationRef.get();
        final Instant nodeCoolDownTimestamp = nodeCoolDownTimestampRef.get();
        final BlockNodeEndpoint streamingEndpoint = configuration.streamingEndpoint();

        sb.append("Block Node (");
        sb.append("host: ").append(streamingEndpoint.host());
        sb.append(", port: ").append(streamingEndpoint.port());
        sb.append(", priority: ").append(configuration.priority());
        sb.append(", isStreamingCandidate: ").append(isStreamingCandidate());
        sb.append(", coolDownTimestamp: ").append(nodeCoolDownTimestamp == null ? "-" : nodeCoolDownTimestamp);
        sb.append(", activeConnections: ").append(localActiveStreamingConnectionCount);
        sb.append(")\n  Connection History");

        if (connectionHistories.isEmpty()) {
            sb.append("\n    <none>");
        } else {
            final List<ConnectionHistory> sortedHistory = connectionHistories.values().stream()
                    .sorted(Comparator.comparing(ConnectionHistory::createTimestamp)
                            .reversed())
                    .toList();
            for (final ConnectionHistory history : sortedHistory) {
                sb.append("\n    ").append(history.connectionId).append(" => ");
                sb.append("created: ").append(history.createTimestamp);
                sb.append(", activated: ").append(history.activeTimestamp == null ? "-" : history.activeTimestamp);
                sb.append(", closed: ").append(history.closeTimestamp == null ? "-" : history.closeTimestamp);
                final Duration duration = history.duration();
                sb.append(", duration: ").append(duration == null ? "-" : duration);
                sb.append(", closeReason: ").append(history.closeReason == null ? "-" : history.closeReason);
                sb.append(", blocksSent: ").append(history.numBlocksSent == null ? "-" : history.numBlocksSent);
            }
        }
    }

    /**
     * @return the block node stats associated with this block node
     */
    @NonNull
    BlockNodeStats stats() {
        return stats;
    }

    /**
     * @return the current configuration associated with this block node
     */
    @NonNull
    BlockNodeConfiguration configuration() {
        return configurationRef.get();
    }

    /**
     * Retrieves whether this block node may be streamed to with a new connection. If this block node is marked as
     * terminated, it is currently in cool down, or there is already an active connection, then this block node will
     * not be considered as a candidate to stream to.
     *
     * @return true if this block node can be streamed to, else false
     */
    boolean isStreamingCandidate() {
        return !isTerminated.get() && !isNodeInCoolDown() && activeStreamingConnectionRef.get() == null;
    }

    /**
     * Updates the active configuration associated with this block node. If there is an active connection, the
     * connection will be closed at the next block boundary.
     *
     * @param configuration the new configuration to apply for this block node
     */
    void onConfigUpdate(@NonNull final BlockNodeConfiguration configuration) {
        configurationRef.set(requireNonNull(configuration, "Configuration is required"));
        nodeCoolDownTimestampRef.set(null);

        final BlockNodeStreamingConnection activeConnection = activeStreamingConnectionRef.get();
        if (activeConnection != null) {
            activeConnection.closeAtBlockBoundary(CloseReason.CONFIG_UPDATE);
        }
    }

    /**
     * Retrieves whether this block node can be removed from any tracking. If this block node is marked as terminated,
     * and it has no active connection, this block node can safely be removed.
     *
     * @return
     */
    boolean isRemovable() {
        return isTerminated.get() && activeStreamingConnectionRef.get() == null;
    }

    /**
     * Closes the active connection associated with this block node, if one exists.
     *
     * @param closeReason the reason for closing the connection
     */
    void closeConnection(@NonNull final CloseReason closeReason) {
        final BlockNodeStreamingConnection activeConnection = activeStreamingConnectionRef.get();
        if (activeConnection != null) {
            activeConnection.closeAtBlockBoundary(closeReason);
        }
    }

    /**
     * Marks this block node as being terminated - typically as the result of being removed from the block node
     * configuration set. If there is an active connection, it will be closed too.
     *
     * @param closeReason the reason to indicate why the active connection (if it exists) is being closed
     */
    void onTerminate(final CloseReason closeReason) {
        isTerminated.set(true);
        closeConnection(closeReason);
    }

    /**
     * Determines if this block node is actively in a cool down period. The cool down period is related to the most
     * recent closed connection and whether the reason why the connection was closed requires a cool down.
     *
     * @return true if this block node is currently in a cool down period, else false
     */
    private boolean isNodeInCoolDown() {
        final Instant nodeCoolDownTimestamp = nodeCoolDownTimestampRef.get();
        return nodeCoolDownTimestamp != null && nodeCoolDownTimestamp.isAfter(Instant.now(clock));
    }

    /**
     * Notifies that this block node has a connection that has become active. As a result of this, state such as active
     * connection counters will be incremented and a new connection history entry is created. Lastly, if there was an
     * existing active connection already associated with this block node, that connection will be closed.
     *
     * @param connection the new active connection associated to this block node
     */
    void onActive(@NonNull final BlockNodeStreamingConnection connection) {
        requireNonNull(connection, "Connection is required");
        final BlockNodeEndpoint endpoint = connection.configuration().streamingEndpoint();

        final BlockNodeStreamingConnection oldConnection = activeStreamingConnectionRef.getAndSet(connection);
        if (oldConnection != null) {
            logger.info(
                    "[{}:{}] Block node has multiple active connections (new: {}, old: {}); closing old connection",
                    endpoint.host(),
                    endpoint.port(),
                    connection.connectionId(),
                    oldConnection.connectionId());
            oldConnection.closeAtBlockBoundary(CloseReason.NEW_CONNECTION);
        }

        final ConnectionHistory connectionHistory = new ConnectionHistory(connection);
        connectionHistories.put(connection.connectionId(), connectionHistory);

        connectionHistory.onActive(connection);
        globalActiveStreamingConnectionCount.incrementAndGet();
        localActiveStreamingConnectionCount.incrementAndGet();

        pruneOldHistory();
    }

    /**
     * Prune the connection history to remove old entries. Entries that indicate the connection has been closed are
     * eligible for being removed. In some cases, a connection may be delayed in closing (e.g. closing at a block
     * boundary that takes a bit to process) and as such we should avoid removing those entries prematurely.
     */
    private void pruneOldHistory() {
        if (connectionHistories.size() <= MAX_HISTORY_ENTRIES) {
            return;
        }

        // create a copy of the history map
        final SortedSet<ConnectionId> sortedIds = new TreeSet<>(Comparator.comparing(ConnectionId::sequenceNumber));
        sortedIds.addAll(connectionHistories.keySet());

        for (final ConnectionId id : sortedIds) {
            final ConnectionHistory entry = connectionHistories.get(id);
            if (entry.closeTimestamp != null) {
                // the connection history indicates closed, so we can safely remove this entry
                connectionHistories.remove(id, entry);
                final BlockNodeEndpoint endpoint = configuration().streamingEndpoint();
                logger.trace(
                        "[{}:{}] Connection (id: {}) removed from block node history",
                        endpoint.host(),
                        endpoint.port(),
                        id);
            }

            if (connectionHistories.size() <= MAX_HISTORY_ENTRIES) {
                break;
            }
        }
    }

    /**
     * Notifies that this block node has a connection that has been closed. As a result of this, state such as active
     * connection counters will be decremented and the history entry associated with the connection will be updated.
     * Lastly, if the reason the connection was closed requires a cool down, then the cool down timestamp will be
     * updated.
     *
     * @param connection the connection that was closed
     */
    void onClose(@NonNull final BlockNodeStreamingConnection connection) {
        requireNonNull(connection, "Connection is required");

        activeStreamingConnectionRef.compareAndSet(connection, null);

        final BlockNodeEndpoint endpoint = connection.configuration().streamingEndpoint();
        final ConnectionHistory connHistory = connectionHistories.get(connection.connectionId());

        if (connHistory == null) {
            logger.warn(
                    "[{}:{}] Block node does not have a history entry for connection ({})",
                    endpoint.host(),
                    endpoint.port(),
                    connection.connectionId());
            return;
        }

        globalActiveStreamingConnectionCount.decrementAndGet();
        localActiveStreamingConnectionCount.decrementAndGet();
        connHistory.onClose(connection);

        final CloseReason closeReason = connHistory.closeReason;
        if (closeReason.isDeviantCloseReason()) {
            applyCoolDown(new DeviantConnectionClose(closeReason));
        }
    }

    /**
     * Reason for why a cool down is being applied.
     */
    sealed interface CoolDownReason permits ServiceConnectionFailure, DeviantConnectionClose {}

    /**
     * Indicates a cool down is required due to a failure with service connections to the block node - e.g. timed out
     * waiting to get server status.
     */
    record ServiceConnectionFailure() implements CoolDownReason {}

    /**
     * Indicates a cool down is required due to a deviant (i.e. unexpected) streaming connection close - e.g. the
     * connection experienced a network error.
     *
     * @param closeReason
     */
    record DeviantConnectionClose(CloseReason closeReason) implements CoolDownReason {}

    /**
     * Applies a cool down to this block node.
     *
     * @param reason the reason for the cool down
     */
    void applyCoolDown(@NonNull final CoolDownReason reason) {
        final CoolDownType coolDownType =
                switch (reason) {
                    case ServiceConnectionFailure() -> CoolDownType.BASIC;
                    case DeviantConnectionClose(final CloseReason closeReason) -> closeReason.coolDownType();
                };
        final BlockNodeConnectionConfig bncConfig =
                configProvider.getConfiguration().getConfigData(BlockNodeConnectionConfig.class);
        final int coolDownSeconds =
                switch (coolDownType) {
                    case BASIC -> bncConfig.basicNodeCoolDownSeconds();
                    case EXTENDED -> bncConfig.extendedNodeCoolDownSeconds();
                    default -> 0;
                };

        if (coolDownSeconds == 0) {
            return;
        }

        final Instant newCoolDownTimestamp = Instant.now(clock).plusSeconds(coolDownSeconds);
        final Instant actualCoolDownTimestamp = nodeCoolDownTimestampRef.updateAndGet(existingCoolDown -> {
            if (existingCoolDown == null || existingCoolDown.isBefore(newCoolDownTimestamp)) {
                return newCoolDownTimestamp;
            }

            return existingCoolDown;
        });

        if (!newCoolDownTimestamp.equals(actualCoolDownTimestamp)) {
            // there is another cool down active that extends beyond what this invocation wanted the cool down to be
            // leave the longer cool down in place
            logger.debug(
                    "An cool down with a later end is already exists (existing: {}, wanted: {}) - will not modify the cool down",
                    actualCoolDownTimestamp,
                    newCoolDownTimestamp);
            return;
        }

        final BlockNodeEndpoint endpoint = configuration().streamingEndpoint();

        logger.warn(
                "[{}:{}] Block node is in cool down until {} (reason: {})",
                endpoint.host(),
                endpoint.port(),
                newCoolDownTimestamp,
                reason);
        nodeCoolDownTimestampRef.set(newCoolDownTimestamp);
    }

    /**
     * Simple data structure to track lifecycle events for a given connection.
     */
    static class ConnectionHistory {
        final ConnectionId connectionId;
        final Instant createTimestamp;
        Instant activeTimestamp;
        Instant closeTimestamp;
        CloseReason closeReason;
        Integer numBlocksSent;

        ConnectionHistory(@NonNull final BlockNodeStreamingConnection connection) {
            connectionId = connection.connectionId();
            createTimestamp = connection.createTimestamp();
        }

        ConnectionHistory(
                final ConnectionId connectionId,
                final Instant createTimestamp,
                final Instant activeTimestamp,
                final Instant closeTimestamp,
                final CloseReason closeReason,
                final Integer numBlocksSent) {
            this.connectionId = connectionId;
            this.createTimestamp = createTimestamp;
            this.activeTimestamp = activeTimestamp;
            this.closeTimestamp = closeTimestamp;
            this.closeReason = closeReason;
            this.numBlocksSent = numBlocksSent;
        }

        @NonNull
        Instant createTimestamp() {
            return createTimestamp;
        }

        /**
         * Updates the internal state to reflect that the specified connection has become active.
         *
         * @param connection the connection that has become active
         */
        void onActive(@NonNull final BlockNodeStreamingConnection connection) {
            activeTimestamp = connection.activeTimestamp();
        }

        /**
         * Updates internal state to reflect the specified connection being closed. If the connection was closed without
         * a reason, the reason will be recorded as {@link CloseReason#UNKNOWN}.
         *
         * @param connection the connection that was closed
         */
        void onClose(@NonNull final BlockNodeStreamingConnection connection) {
            closeTimestamp = connection.closeTimestamp();
            closeReason = connection.closeReason();
            if (closeReason == null) {
                final BlockNodeEndpoint endpoint = connection.configuration().streamingEndpoint();
                logger.warn(
                        "[{}:{}] Block node connection ({}) was closed without a close reason",
                        endpoint.host(),
                        endpoint.port(),
                        connection.connectionId());
                closeReason = CloseReason.UNKNOWN;
            }
            numBlocksSent = connection.numberOfBlocksSent();
        }

        /**
         * @return the duration of the connection assuming it was closed, else null
         */
        @Nullable
        Duration duration() {
            if (activeTimestamp == null || closeTimestamp == null) {
                return null;
            }

            return Duration.between(activeTimestamp, closeTimestamp);
        }
    }
}
