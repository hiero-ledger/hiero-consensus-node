// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.PublishStreamResponse;
import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.internal.network.BlockNodeConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.helidon.common.tls.Tls;
import io.helidon.webclient.grpc.GrpcClient;
import io.helidon.webclient.grpc.GrpcClientMethodDescriptor;
import io.helidon.webclient.grpc.GrpcClientProtocolConfig;
import io.helidon.webclient.grpc.GrpcServiceClient;
import io.helidon.webclient.grpc.GrpcServiceDescriptor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages connections to block nodes, connection lifecycle and node selection.
 * It is also responsible for retrying with exponential backoff if a connection fails.
 */
public class BlockNodeConnectionManager {
    public static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Logger logger = LogManager.getLogger(BlockNodeConnectionManager.class);
    private static final long RETRY_BACKOFF_MULTIPLIER = 2;
    private static final String GRPC_END_POINT =
            BlockStreamServiceGrpc.getPublishBlockStreamMethod().getBareMethodName();
    // Maximum retry delay to prevent excessively long waits
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(10);

    // Add a random number generator for retry jitter
    private final Random random = new Random();

    private final Map<BlockNodeConfig, BlockNodeConnection> connections;
    private final Map<BlockNodeConfig, Long> lastVerifiedBlockPerConnection;

    private final BlockNodeConfigExtractor blockNodeConfigurations;
    private final BlockStreamStateManager blockStreamStateManager;
    private final ScheduledExecutorService connectionExecutor = Executors.newScheduledThreadPool(4);
    private final BlockStreamMetrics blockStreamMetrics;

    /**
     * Creates a new BlockNodeConnectionManager with the given configuration from disk.
     * @param blockNodeConfigExtractor the block node configuration extractor
     * @param blockStreamStateManager the block stream state manager
     */
    public BlockNodeConnectionManager(
            @NonNull final BlockNodeConfigExtractor blockNodeConfigExtractor,
            @NonNull final BlockStreamStateManager blockStreamStateManager,
            @NonNull final BlockStreamMetrics blockStreamMetrics) {
        this.blockNodeConfigurations =
                requireNonNull(blockNodeConfigExtractor, "blockNodeConfigExtractor must not be null");
        this.blockStreamStateManager =
                requireNonNull(blockStreamStateManager, "blockStreamStateManager must not be null");

        this.connections = new ConcurrentHashMap<>();
        this.lastVerifiedBlockPerConnection = new ConcurrentHashMap<>();
        this.blockStreamMetrics = blockStreamMetrics;
    }

    /**
     * @return the gRPC endpoint for publish block stream
     */
    public String getGrpcEndPoint() {
        return GRPC_END_POINT;
    }

    private GrpcServiceClient createNewGrpcClient(@NonNull BlockNodeConfig node) {
        final GrpcClient client = GrpcClient.builder()
                .tls(Tls.builder().enabled(false).build())
                .baseUri("http://" + node.address() + ":" + node.port())
                .protocolConfig(GrpcClientProtocolConfig.builder()
                        .abortPollTimeExpired(false)
                        .pollWaitTime(Duration.ofSeconds(30))
                        .build())
                .keepAlive(true)
                .build();

        return client.serviceClient(GrpcServiceDescriptor.builder()
                .serviceName(BlockStreamServiceGrpc.SERVICE_NAME)
                .putMethod(
                        GRPC_END_POINT,
                        GrpcClientMethodDescriptor.bidirectional(BlockStreamServiceGrpc.SERVICE_NAME, GRPC_END_POINT)
                                .requestType(PublishStreamRequest.class)
                                .responseType(PublishStreamResponse.class)
                                .marshallerSupplier(new RequestResponseMarshaller.Supplier())
                                .build())
                .build());
    }

    /**
     * Handles connection errors reported by an active BlockNodeConnection.
     * Schedules a retry for the failed connection and attempts to select a new active node.
     *
     * @param connection the connection that received the error
     */
    public void handleConnectionError(@NonNull final BlockNodeConnection connection) {
        synchronized (connections) {
            logger.warn(
                    "[{}] Handling connection error for {}",
                    Thread.currentThread().getName(),
                    blockNodeName(connection.getNodeConfig()));
            // Schedule retry for the failed connection (will use INITIAL_RETRY_DELAY)
            scheduleRetry(connection, INITIAL_RETRY_DELAY);
            // Immediately try to find and connect to the next available node
            selectBlockNodeForStreaming();
        }
    }

    /**
     * Schedules a connection attempt (or retry) for the given Block Node connection
     * after the specified delay. Handles adding/removing the connection from the retry map.
     *
     * @param connection the connection to schedule a retry for
     * @param initialDelay the delay before the first attempt in this sequence executes
     */
    public void scheduleRetry(@NonNull final BlockNodeConnection connection, @NonNull final Duration initialDelay) {
        requireNonNull(connection);
        requireNonNull(initialDelay);
        final var nodeConfig = connection.getNodeConfig();

        long delayMillis = initialDelay.toMillis();
        // Ensure non-negative delay
        if (delayMillis < 0) {
            delayMillis = 0;
        }

        logger.info(
                "[{}] Scheduling connection task for block node {} in {} ms",
                Thread.currentThread().getName(),
                blockNodeName(nodeConfig),
                delayMillis);

        // Schedule the first attempt using the connectionExecutor
        try {
            connectionExecutor.schedule(
                    new BlockNodeConnectionTask(connection, initialDelay), delayMillis, TimeUnit.MILLISECONDS);
            logger.debug(
                    "[{}] Successfully scheduled connection task for {}.",
                    Thread.currentThread().getName(),
                    blockNodeName(nodeConfig));
        } catch (Exception e) {
            logger.error("Failed to schedule connection task for block node {}.", blockNodeName(nodeConfig), e);
            // Consider closing the connection object if scheduling fails
            connection.close();
        }
    }

    /**
     * Shuts down the connection manager, closing active connection.
     */
    public void shutdown() {
        connectionExecutor.shutdown();
        try {
            if (!connectionExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.error("Failed to shut down connection executor within timeout");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            synchronized (connections) {
                // Ensure activeConnection is not null before closing
                // TODO
                // Also close connections still marked for retry
                connections.values().forEach(conn -> {
                    if (conn != NoOpConnection.INSTANCE) { // Avoid closing placeholder
                        conn.close();
                    }
                });
                connections.clear();
            }
        }
    }

    /**
     * Waits for at least one connection to be established.
     * Initiates connection attempts and waits using a condition variable.
     *
     * @param timeout the maximum time to wait
     * @return true if at least one connection was established, false if the timeout elapsed before any connections were established
     */
    public boolean waitForConnection(Duration timeout) {
        // Initiate connection attempts first by selecting nodes and scheduling them
        selectBlockNodeForStreaming();

        long remainingSeconds = timeout.toSeconds();

        // Loop while no connection is active
        boolean connectionNotActive = true;
        while (connectionNotActive) {
            // Check if timeout expired
            if (remainingSeconds <= 0L) {
                logger.warn("Timeout waiting for initial block node connection after {}", timeout);
                return false; // Timeout expired
            }

            try {
                // Wait for a signal or until the remaining timeout elapses
                Thread.sleep(1000);
                remainingSeconds -= 1L;
                connectionNotActive = connections.values().stream()
                        .noneMatch(connection -> connection.getConnectionState().equals(ConnectionState.ACTIVE));
                logger.info(
                        "Waiting for Block Node connection to become ACTIVE... Remaining time: {} seconds",
                        remainingSeconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for block node connection", e);
                return false; // Interrupted
            }
        }
        logger.info("A Block Node connection has become ACTIVE");
        return true;
    }

    static String blockNodeName(@Nullable final BlockNodeConfig node) {
        return node != null ? node.address() + ":" + node.port() : "null";
    }

    public void openBlock(long blockNumber) {
        final BlockNodeConnection connection = connections.values().stream()
                .filter(i -> i.getConnectionState().equals(ConnectionState.ACTIVE))
                .findFirst()
                .orElse(null);
        if (connection == null) {
            logger.warn(
                    "[{}] No active connections available for streaming block {}",
                    Thread.currentThread().getName(),
                    blockNumber);
            return;
        }

        if (connection.isActive()) {
            if (connection.getCurrentBlockNumber() == -1) {
                connection.setCurrentBlockNumber(blockNumber);
            }
            connection.notifyNewBlockAvailable();
        }
    }

    public void notifyConnectionsOfNewRequest() {
        final BlockNodeConnection connection = connections.values().stream()
                .filter(i -> i.getConnectionState().equals(ConnectionState.ACTIVE))
                .findFirst()
                .orElse(null);
        if (connection == null) {
            return;
        }

        if (connection.isActive()) {
            connection.notifyNewRequestAvailable();
        }
    }

    /**
     * Selects the next highest priority available block node and schedules a connection attempt.
     */
    @VisibleForTesting
    void selectBlockNodeForStreaming() {
        synchronized (connections) {
            final BlockNodeConfig selectedNode = getNextPriorityBlockNode();

            if (selectedNode == null) {
                logger.debug(
                        "[{}] No other block nodes found for attempted streaming.",
                        Thread.currentThread().getName());
                return;
            } else {
                logger.debug(
                        "[{}] Selected block node {} for connection attempt.",
                        Thread.currentThread().getName(),
                        blockNodeName(selectedNode));
            }

            // If we selected a node, schedule the connection attempt.
            connectToNode(selectedNode);
        }
    }

    public BlockNodeConfig getNextPriorityBlockNode() {
        logger.info(
                "[{}] Establishing connection to block node based on priorities",
                Thread.currentThread().getName());
        List<BlockNodeConfig> availableNodes = blockNodeConfigurations.getAllNodes();

        final Map<Integer, List<BlockNodeConfig>> priorityGroups =
                availableNodes.stream().collect(Collectors.groupingBy(BlockNodeConfig::priority));
        final List<Integer> sortedPriorities = new ArrayList<>(priorityGroups.keySet());
        sortedPriorities.sort(Integer::compare);

        // Find the current lowest priority which is the priority of the active connection
        final int currentMinPriority = getCurrentMinPriority();

        BlockNodeConfig selectedNode = null;
        for (Integer priority : sortedPriorities) {
            // Skip over any nodes that have less priority than the current minimum
            // Correction: We want the lowest priority number (highest priority)
            // But we only consider switching *up* in priority, so skip >= currentMinPriority
            if (priority >= currentMinPriority) continue;

            // Filter nodes not in retry, and select one randomly
            final List<BlockNodeConfig> nextPriorityGroup = priorityGroups.get(priority).stream()
                    .filter(node -> !connections.containsKey(node)
                            || !connections
                                    .get(node)
                                    .getConnectionState()
                                    .equals(ConnectionState.UNINITIALIZED)) // Check if node is marked for retry
                    .toList();

            if (!nextPriorityGroup.isEmpty()) {
                // Select randomly among available nodes in the highest priority group found so far
                final var randomIndex = random.nextInt(nextPriorityGroup.size());
                selectedNode = nextPriorityGroup.get(randomIndex);
                break; // Found the highest priority group with available nodes
            }
        }
        // If no higher priority node is available, check the current priority group (if active)
        if (selectedNode == null && currentMinPriority != Integer.MAX_VALUE) {
            final List<BlockNodeConfig> currentPriorityGroup = priorityGroups.get(currentMinPriority).stream()
                    .filter(node -> !isRetrying(node)) // Check if node is marked for retry
                    .toList();
            if (!currentPriorityGroup.isEmpty()) {
                selectedNode = currentPriorityGroup.get(random.nextInt(currentPriorityGroup.size()));
            }
        }

        // If still null, maybe check lower priorities only if NO connection is active?
        // This logic primarily targets finding the BEST node, assuming retries handle eventual connection.
        // If all nodes are in retry, selectedNode remains null, handled by caller.

        return selectedNode;
    }

    private int getCurrentMinPriority() {
        // Find the current lowest priority which is the priority of the active connection
        return connections.values().stream()
                .filter(connection -> connection.getConnectionState().equals(ConnectionState.ACTIVE))
                .map(BlockNodeConnection::getNodeConfig)
                .map(BlockNodeConfig::priority)
                .min(Integer::compareTo)
                .orElse(Integer.MAX_VALUE);
    }

    /**
     * Creates a BlockNodeConnection instance and immediately schedules the *first*
     * connection attempt using the retry mechanism (with zero initial delay).
     *
     * @param node The configuration of the node to connect to.
     */
    private void connectToNode(@NonNull BlockNodeConfig node) {
        logger.info("Scheduling connection attempt for block node {}", blockNodeName(node));
        BlockNodeConnection connection = null; // Declare outside try
        try {
            // Create the connection object
            final GrpcServiceClient grpcClient = createNewGrpcClient(node);
            connection = new BlockNodeConnection(
                    node, this, blockStreamStateManager, grpcClient, connectionExecutor, blockStreamMetrics);

            connections.put(node, connection);
            // Immediately schedule the FIRST connection attempt.
            scheduleRetry(connection, Duration.ZERO);
        } catch (Exception e) {
            // Catch potential errors during GrpcServiceClient or BlockNodeConnection creation
            logger.error(
                    "Failed during creation or initial scheduling for block node {}. Node will not be retried until selected again.",
                    blockNodeName(node),
                    e);
            // DO NOT add NoOpConnection to connectionsInRetry here.
            // If creation fails, the node is simply not available now.
            // If scheduleRetry failed, it already logged and cleaned up connectionsInRetry.
        }
    }

    @VisibleForTesting
    boolean isRetrying(BlockNodeConnection connection) {
        if (connection == null) return false;
        return isRetrying(connection.getNodeConfig());
    }

    private boolean isRetrying(BlockNodeConfig config) {
        if (config == null) return false;
        // No external lock needed, ConcurrentHashMap handles its own concurrency for single ops.
        return connections.containsKey(config);
    }

    public boolean higherPriorityStarted(BlockNodeConnection blockNodeConnection) {
        synchronized (connections) {
            // Find a pending connection with the highest priority greater than the current connection
            BlockNodeConnection highestPri = null;
            for (BlockNodeConnection connection : this.connections.values()) {
                if (connection.getConnectionState().equals(ConnectionState.PENDING)
                        && connection.getNodeConfig().priority()
                                < blockNodeConnection.getNodeConfig().priority()) {
                    if (highestPri == null
                            || connection.getNodeConfig().priority()
                                    < highestPri.getNodeConfig().priority()) {
                        // If no connection is found or the current one has a higher priority, update the reference
                        highestPri = connection;
                    }
                }
            }

            if (highestPri != null) {
                // Found a higher priority pending connection,
                highestPri.updateConnectionState(ConnectionState.ACTIVE);
                highestPri.startRequestWorker();
                // Log the transition of this higher priority connection to active
                logger.debug(
                        "[{}] Transitioning higher priority pending connection: {} Priority: {} to ACTIVE",
                        Thread.currentThread().getName(),
                        blockNodeName(highestPri.getNodeConfig()),
                        highestPri.getNodeConfig().priority());

                // Close the current connection and remove it from the connections map
                blockNodeConnection.close();
                connections.remove(blockNodeConnection.getNodeConfig());
                return true;
            }
            return false;
        }
    }

    // This class exists solely to avoid checking for null every time we reference a connection in connectionsInRetry
    // And to handle cases where connection object creation might fail in connectToNode
    private static class NoOpConnection extends BlockNodeConnection {
        static final NoOpConnection INSTANCE;

        static {
            INSTANCE = new NoOpConnection();
        }

        private NoOpConnection() {
            // Provide minimal valid state for super constructor if needed, or make super allow nulls
            // Assuming BlockNodeConfig.DEFAULT exists and manager can be null for this placeholder
            super(BlockNodeConfig.DEFAULT, null, null, null, null, null);
        }

        @Override
        public void onNext(PublishStreamResponse response) {
            // No-op
        }

        @Override
        public void onError(Throwable throwable) {
            // No-op
        }

        @Override
        public void onCompleted() {
            // No-op
        }

        @Override
        public void sendRequest(@NonNull final PublishStreamRequest request) {
            // No-op
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public void close() {
            // No-op
        }

        @Override
        public BlockNodeConfig getNodeConfig() {
            return BlockNodeConfig.DEFAULT; // Return default or null?
        }

        @Override
        public void updateConnectionState(ConnectionState newState) {
            /* No-op */
        }

        @Override
        public void setCurrentBlockNumber(long blockNumber) {
            /* No-op */
        }

        @Override
        public long getCurrentBlockNumber() {
            return -1L;
        }

        @Override
        public void notifyNewBlockAvailable() {
            /* No-op */
        }

        @Override
        public void notifyNewRequestAvailable() {
            /* No-op */
        }

        @Override
        public void jumpToBlock(long blockNumber) {
            /* No-op */
        }
    }

    /**
     * @param blockNodeConfig the configuration for the block node
     * @param blockNumber the block number of the last verified block
     */
    public void updateLastVerifiedBlock(
            @NonNull final BlockNodeConfig blockNodeConfig, @Nullable final Long blockNumber) {
        requireNonNull(blockNodeConfig);

        final Long latestBlock = getLastVerifiedBlock(blockNodeConfig);
        if (blockNumber != null && blockNumber > latestBlock) {
            lastVerifiedBlockPerConnection.put(blockNodeConfig, blockNumber);
            blockStreamMetrics.setLatestAcknowledgedBlockNumber(blockNumber);
        }
    }

    /**
     * @param blockNodeConfig the configuration for the block node
     * @return the last verified block number by the given block node.
     */
    public Long getLastVerifiedBlock(@NonNull final BlockNodeConfig blockNodeConfig) {
        requireNonNull(blockNodeConfig);
        return lastVerifiedBlockPerConnection.computeIfAbsent(blockNodeConfig, key -> -1L);
    }

    /**
     * @param blockNumber the block number to check for acknowledgements
     * @return whether the block has been acknowledged by any connection.
     */
    public boolean isBlockAlreadyAcknowledged(@NonNull final Long blockNumber) {
        requireNonNull(blockNumber);
        return lastVerifiedBlockPerConnection.values().stream()
                .anyMatch(verifiedBlock -> verifiedBlock.equals(blockNumber));
    }

    /**
     * Runnable task to handle the connection attempt logic.
     * Schedules itself for subsequent retries upon failure using the connectionExecutor.
     * Handles setting active connection and signaling on success.
     */
    private class BlockNodeConnectionTask implements Runnable {
        private final BlockNodeConnection connection;
        private Duration currentBackoffDelay; // Represents the delay *before* the next attempt

        BlockNodeConnectionTask(@NonNull final BlockNodeConnection connection, @NonNull final Duration initialDelay) {
            this.connection = requireNonNull(connection);
            // Ensure initial delay is non-negative for backoff calculation
            this.currentBackoffDelay = initialDelay.isNegative() ? Duration.ZERO : initialDelay;
        }

        @Override
        public void run() {
            final var nodeConfig = connection.getNodeConfig();
            try {
                synchronized (connections) {
                    logger.debug(
                            "[{}] Running connection task for block node {} ConnectionState: {}",
                            Thread.currentThread().getName(),
                            blockNodeName(nodeConfig),
                            connection.getConnectionState());

                    // Check if the connection is already active
                    if (connection.getConnectionState().equals(ConnectionState.ACTIVE)) {
                        logger.debug(
                                "[{}] Connection task for block node {} is already active",
                                Thread.currentThread().getName(),
                                blockNodeName(nodeConfig));
                    } else if (connections.values().stream()
                            .anyMatch(c -> c.getConnectionState().equals(ConnectionState.ACTIVE)
                                    && c.getNodeConfig().priority() <= nodeConfig.priority())) {
                        // If we have an active connection, and this task is of lower priority, stop rescheduling.
                        logger.debug(
                                "[{}] Connection task for block node {} is stopping due to active connection with higher priority",
                                Thread.currentThread().getName(),
                                blockNodeName(nodeConfig));
                    } else if (connection.getConnectionState().equals(ConnectionState.UNINITIALIZED)) {
                        // This is either the first connection attempt ever or the connection was closed and needs
                        // to be re-established
                        connection.createRequestObserver(); // This may throw an exception if the connection fails
                        connection.updateConnectionState(ConnectionState.PENDING);
                        logger.debug(
                                "[{}] Connection task for block node {} ConnectionState: {}",
                                Thread.currentThread().getName(),
                                blockNodeName(nodeConfig),
                                connection.getConnectionState());
                        transitionActiveIfNoConnectionsAreActive(nodeConfig);
                    } else if (connection.getConnectionState().equals(ConnectionState.PENDING)) {
                        transitionActiveIfNoConnectionsAreActive(nodeConfig);
                    }
                }
            } catch (Exception e) {
                // Calculate next delay based on the *previous* backoff delay for this task instance
                Duration nextDelay = currentBackoffDelay.isZero()
                        ? INITIAL_RETRY_DELAY // Start with initial delay if previous was 0
                        : currentBackoffDelay.multipliedBy(RETRY_BACKOFF_MULTIPLIER);

                if (nextDelay.compareTo(MAX_RETRY_DELAY) > 0) {
                    nextDelay = MAX_RETRY_DELAY;
                }

                // Apply jitter
                long jitteredDelayMs;
                if (nextDelay.toMillis() > 0) {
                    jitteredDelayMs = nextDelay.toMillis() / 2 + random.nextLong(nextDelay.toMillis() / 2 + 1);
                } else {
                    // Should not happen if INITIAL_RETRY_DELAY > 0, but handle defensively
                    jitteredDelayMs = INITIAL_RETRY_DELAY.toMillis() / 2
                            + random.nextLong(INITIAL_RETRY_DELAY.toMillis() / 2 + 1);
                    if (jitteredDelayMs <= 0) jitteredDelayMs = 1; // Ensure positive delay
                }

                // Update backoff delay *for the next run* of this task instance
                this.currentBackoffDelay = Duration.ofMillis(jitteredDelayMs);

                logger.warn(
                        "[{}] Stream establishment attempt failed for block node {}: {}. Rescheduling in {} ms",
                        Thread.currentThread().getName(),
                        blockNodeName(nodeConfig),
                        e.getMessage(),
                        jitteredDelayMs);

                // Reschedule this task using the calculated jittered delay
                try {
                    connectionExecutor.schedule(this, jitteredDelayMs, TimeUnit.MILLISECONDS);
                } catch (Exception scheduleEx) {
                    logger.error(
                            "Failed to reschedule connection attempt for block node {}. Removing from retry map.",
                            blockNodeName(nodeConfig),
                            scheduleEx);
                    // If rescheduling fails, remove from connections map. A periodic task will handle checking if there
                    // are no longer any connections
                    connections.remove(nodeConfig);
                    // We might also want to explicitly close the connection object here if rescheduling fails
                    // catastrophically
                    connection.close();
                }
            }
        }

        private void transitionActiveIfNoConnectionsAreActive(BlockNodeConfig nodeConfig) {
            if (connections.values().stream()
                    .noneMatch(connection -> connection.getState().equals(ConnectionState.ACTIVE))) {
                connection.updateConnectionState(ConnectionState.ACTIVE);
                connection.startRequestWorker();
                logger.debug(
                        "[{}] Connection task for block node {} ConnectionState: {}",
                        Thread.currentThread().getName(),
                        blockNodeName(nodeConfig),
                        connection.getConnectionState());
            }
        }
    }
}
