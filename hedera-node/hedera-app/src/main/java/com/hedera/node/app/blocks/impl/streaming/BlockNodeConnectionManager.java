// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.LONGER_RETRY_DELAY;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.PublishStreamResponse;
import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState;
import com.hedera.node.app.blocks.impl.streaming.BlockStreamStateManager.BlockStreamQueueItem;
import com.hedera.node.app.blocks.impl.streaming.BlockStreamStateManager.BlockStreamQueueItemType;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.node.internal.network.BlockNodeConnectionInfo;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.helidon.common.tls.Tls;
import io.helidon.webclient.grpc.GrpcClient;
import io.helidon.webclient.grpc.GrpcClientMethodDescriptor;
import io.helidon.webclient.grpc.GrpcClientProtocolConfig;
import io.helidon.webclient.grpc.GrpcServiceClient;
import io.helidon.webclient.grpc.GrpcServiceDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages connections to block nodes in a Hedera network, handling connection lifecycle, node selection,
 * and retry mechanisms. This manager is responsible for:
 * <ul>
 *   <li>Establishing and maintaining connections to block nodes</li>
 *   <li>Managing connection states and lifecycle</li>
 *   <li>Implementing priority-based node selection</li>
 *   <li>Handling connection failures with exponential backoff</li>
 *   <li>Coordinating block streaming across connections</li>
 * </ul>
 */
public class BlockNodeConnectionManager {
    /**
     * Initial retry delay for connection attempts.
     */
    public static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(1);

    private static final int PROCESSOR_LOOP_DELAY_MS = 10;
    private static final Logger logger = LogManager.getLogger(BlockNodeConnectionManager.class);
    private static final long RETRY_BACKOFF_MULTIPLIER = 2;
    private static final String GRPC_END_POINT =
            BlockStreamServiceGrpc.getPublishBlockStreamMethod().getBareMethodName();
    // Maximum retry delay to prevent excessively long waits
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(10);

    // Add a random number generator for retry jitter
    private final Random random = new Random();
    private final Map<BlockNodeConfig, Long> lastVerifiedBlockPerConnection;

    private final BlockStreamStateManager blockStreamStateManager;
    private final ScheduledExecutorService connectionExecutor = Executors.newScheduledThreadPool(4);
    private final BlockStreamMetrics blockStreamMetrics;
    private final ConfigProvider configProvider;
    private List<BlockNodeConfig> availableNodes;
    private final AtomicBoolean blockStreamWorkerThreadRunning = new AtomicBoolean(true);
    private final AtomicLong jumpTargetBlock = new AtomicLong(-1);
    private final AtomicLong streamingBlockNumber = new AtomicLong(-1);
    private int requestIndex = 0;
    private Thread blockStreamWorkerThread;

    /**
     * Creates a new BlockNodeConnectionManager with the given configuration from disk.
     * @param configProvider the configuration to use
     * @param blockStreamStateManager the block stream state manager
     * @param blockStreamMetrics the block stream metrics to track
     */
    public BlockNodeConnectionManager(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockStreamStateManager blockStreamStateManager,
            @NonNull final BlockStreamMetrics blockStreamMetrics) {
        this.configProvider = requireNonNull(configProvider, "configProvider must not be null");
        this.blockStreamStateManager =
                requireNonNull(blockStreamStateManager, "blockStreamStateManager must not be null");
        this.lastVerifiedBlockPerConnection = new ConcurrentHashMap<>();
        this.blockStreamMetrics = requireNonNull(blockStreamMetrics, "blockStreamMetrics must not be null");

        if (configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .streamToBlockNodes()) {
            final var blockNodeConnectionConfigPath = configProvider
                    .getConfiguration()
                    .getConfigData(BlockNodeConnectionConfig.class)
                    .blockNodeConnectionFileDir();

            this.availableNodes = extractBlockNodesConfigurations(blockNodeConnectionConfigPath);
            logger.info("Loaded block node configuration from {}", blockNodeConnectionConfigPath);
            logger.info("Block node configuration: {}", this.availableNodes);

            blockStreamWorkerThread =
                    Thread.ofPlatform().name("BlockStreamWorkerLoop").start(this::blockStreamWorkerLoop);
        }
    }

    /**
     * Extracts block node configurations from the specified configuration file.
     *
     * @param blockNodeConfigPath the path to the block node configuration file
     * @return the configurations for all block nodes
     */
    private List<BlockNodeConfig> extractBlockNodesConfigurations(@NonNull final String blockNodeConfigPath) {
        final var configPath = Paths.get(blockNodeConfigPath, "block-nodes.json");
        try {
            final byte[] jsonConfig = Files.readAllBytes(configPath);
            final BlockNodeConnectionInfo protoConfig = BlockNodeConnectionInfo.JSON.parse(Bytes.wrap(jsonConfig));

            // Convert proto config to internal config objects
            return protoConfig.nodes().stream()
                    .map(node -> new BlockNodeConfig(node.address(), node.port(), node.priority()))
                    .collect(Collectors.toList());
        } catch (IOException | ParseException e) {
            logger.error("Failed to read block node configuration from {}", configPath, e);
            throw new RuntimeException("Failed to read block node configuration from " + configPath, e);
        }
    }

    /**
     * Returns the gRPC endpoint for the block stream service.
     *
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
     * @param initialDelay the delay to wait before retrying the connection
     */
    public void handleConnectionError(@NonNull final BlockNodeConnection connection, @NonNull Duration initialDelay) {
        synchronized (blockStreamStateManager.getConnections()) {
            logger.warn(
                    "[{}] Handling connection error for {}",
                    Thread.currentThread().getName(),
                    blockNodeName(connection.getNodeConfig()));
            // Schedule retry for the failed connection after a delay (initialDelay)
            scheduleRetry(connection, initialDelay, null);
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
    public void scheduleRetry(
            @NonNull final BlockNodeConnection connection, @NonNull final Duration initialDelay, Long blockNumber) {
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
                    new BlockNodeConnectionTask(connection, initialDelay, blockNumber),
                    delayMillis,
                    TimeUnit.MILLISECONDS);
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
     * Gracefully shuts down the connection manager, closing active connection.
     */
    public void shutdown() {
        // Stop the block stream worker loop thread
        blockStreamWorkerThreadRunning.set(false);
        if (blockStreamWorkerThread != null) {
            blockStreamWorkerThread.interrupt();
            try {
                blockStreamWorkerThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted while waiting for block stream worker thread to terminate", e);
            }
        }

        connectionExecutor.shutdown();
        try {
            if (!connectionExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.error("Failed to shut down connection executor within timeout");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        synchronized (blockStreamStateManager.getConnections()) {
            blockStreamStateManager.getConnections().values().forEach(BlockNodeConnection::close);
            blockStreamStateManager.getConnections().clear();
        }
    }

    /**
     * Waits for at least one connection to be established.
     * Initiates connection attempts and waits using a condition variable.
     *
     * @param timeout the maximum duration to wait for a connection to be established
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
                connectionNotActive = blockStreamStateManager.getConnections().values().stream()
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

    /**
     * Opens a block for streaming by setting the target block number.
     * If the connection is already active, it will set the jump target block if the current block number is -1.
     *
     * @param blockNumber the block number to open
     */
    public void openBlock(long blockNumber) {
        final BlockNodeConnection connection = getActiveConnection();
        if (connection == null) {
            logger.warn(
                    "[{}] No active connections available for streaming block {}",
                    Thread.currentThread().getName(),
                    blockNumber);
            return;
        }

        if (connection.isActive()) {
            if (streamingBlockNumber.get() == -1) {
                jumpTargetBlock.set(blockNumber);
            }
        }
    }

    /**
     * Selects the next highest priority available block node and schedules a connection attempt.
     */
    @VisibleForTesting
    void selectBlockNodeForStreaming() {
        synchronized (blockStreamStateManager.getConnections()) {
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

    /**
     * Selects the next available block node based on priority.
     * It will skip over any nodes that are already in retry or have a lower priority than the current active connection.
     *
     * @return the next available block node configuration
     */
    public BlockNodeConfig getNextPriorityBlockNode() {
        logger.info(
                "[{}] Establishing connection to block node based on priorities",
                Thread.currentThread().getName());

        final Map<Integer, List<BlockNodeConfig>> priorityGroups =
                getAvailableNodes().stream().collect(Collectors.groupingBy(BlockNodeConfig::priority));
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
                    .filter(node -> !blockStreamStateManager.getConnections().containsKey(node)
                            || !blockStreamStateManager
                                    .getConnections()
                                    .get(node)
                                    .getConnectionState()
                                    .equals(ConnectionState.PENDING)) // Check if node is marked for retry
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
        return blockStreamStateManager.getConnections().values().stream()
                .filter(connection -> connection.getConnectionState().equals(ConnectionState.ACTIVE))
                .map(BlockNodeConnection::getNodeConfig)
                .map(BlockNodeConfig::priority)
                .min(Integer::compareTo)
                .orElse(Integer.MAX_VALUE);
    }

    /**
     * Returns the active connection if one exists.
     * @return return the active connection or null if none exists
     */
    @VisibleForTesting
    BlockNodeConnection getActiveConnection() {
        return blockStreamStateManager.getActiveConnection();
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

        // Create the connection object
        final GrpcServiceClient grpcClient = createNewGrpcClient(node);
        connection = createBlockNodeConnection(node, grpcClient);

        blockStreamStateManager.getConnections().put(node, connection);
        // Immediately schedule the FIRST connection attempt.
        scheduleRetry(connection, Duration.ZERO, null);
    }

    /**
     * Creates a new connection to a block node with the specified configuration.
     * The connection is initialized in an UNINITIALIZED state and will be managed
     * through its lifecycle based on priority and health.
     *
     * @param node the configuration for the block node to connect to
     * @param grpcClient the gRPC client to use for streaming
     * @return the newly created and initialized block node connection
     */
    public BlockNodeConnection createBlockNodeConnection(
            @NonNull BlockNodeConfig node, @NonNull GrpcServiceClient grpcClient) {
        BlockNodeConnection connection;
        connection = new BlockNodeConnection(
                configProvider, node, this, blockStreamStateManager, grpcClient, blockStreamMetrics);
        return connection;
    }

    @VisibleForTesting
    List<BlockNodeConfig> getAvailableNodes() {
        return availableNodes;
    }

    private boolean isRetrying(BlockNodeConfig config) {
        if (config == null) return false;
        // No external lock needed, ConcurrentHashMap handles its own concurrency for single ops.
        return blockStreamStateManager.getConnections().containsKey(config);
    }

    /**
     * Updates the last verified block number for a specific block node.
     *
     * @param blockNodeConfig the configuration for the block node
     * @param blockNumber the block number of the last verified block
     */
    public void updateLastVerifiedBlock(
            @NonNull final BlockNodeConfig blockNodeConfig, @Nullable final Long blockNumber) {
        requireNonNull(blockNodeConfig);

        final Long latestBlock = getLastVerifiedBlock(blockNodeConfig);
        if (blockNumber != null) {
            if (blockNumber > latestBlock) {
                lastVerifiedBlockPerConnection.put(blockNodeConfig, blockNumber);
            }
            blockStreamStateManager.setLatestAcknowledgedBlock(blockNumber);
        }
    }

    /**
     * Retrieves the last verified block number for a specific block node.
     *
     * @param blockNodeConfig the configuration for the block node
     * @return the last verified block number by the given block node, or -1 if none are verified.
     */
    public Long getLastVerifiedBlock(@NonNull final BlockNodeConfig blockNodeConfig) {
        requireNonNull(blockNodeConfig);
        return lastVerifiedBlockPerConnection.computeIfAbsent(blockNodeConfig, key -> -1L);
    }

    /**
     * Runnable task to handle the connection attempt logic.
     * Schedules itself for subsequent retries upon failure using the connectionExecutor.
     * Handles setting active connection and signaling on success.
     */
    private class BlockNodeConnectionTask implements Runnable {
        private final BlockNodeConnection connection;
        private Duration currentBackoffDelay; // Represents the delay *before* the next attempt

        private final Long blockNumber; // If becoming ACTIVE, the blockNumber to jump to

        BlockNodeConnectionTask(
                @NonNull final BlockNodeConnection connection, @NonNull final Duration initialDelay, Long blockNumber) {
            this.connection = requireNonNull(connection);
            // Ensure initial delay is non-negative for backoff calculation
            this.currentBackoffDelay = initialDelay.isNegative() ? Duration.ZERO : initialDelay;
            this.blockNumber = blockNumber;
        }

        /**
         * Manages the state transitions of gRPC streaming connections to Block Nodes.
         * Connection state transitions are synchronized to ensure thread-safe updates when
         * promoting connections from PENDING to ACTIVE state or handling failures.
         */
        @Override
        public void run() {
            final var nodeConfig = connection.getNodeConfig();
            try {
                synchronized (blockStreamStateManager.getConnections()) {
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
                    } else if (blockStreamStateManager.getConnections().values().stream()
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
                    blockStreamStateManager.getConnections().remove(nodeConfig);
                    // We might also want to explicitly close the connection object here if rescheduling fails
                    // catastrophically
                    connection.close();
                }
            }
        }

        private void transitionActiveIfNoConnectionsAreActive(BlockNodeConfig nodeConfig) {
            if (blockStreamStateManager.getConnections().values().stream()
                    .noneMatch(connection -> connection.getConnectionState().equals(ConnectionState.ACTIVE))) {
                connection.updateConnectionState(ConnectionState.ACTIVE);
                blockStreamStateManager.setActiveConnection(connection);
                jumpTargetBlock.set(Objects.requireNonNullElseGet(
                        blockNumber, blockStreamStateManager::getLowestUnackedBlockNumber));
                logger.debug(
                        "[{}] Connection task for block node {} ConnectionState: {}",
                        Thread.currentThread().getName(),
                        blockNodeName(nodeConfig),
                        connection.getConnectionState());
            }
        }
    }

    private void blockStreamWorkerLoop() {
        while (blockStreamWorkerThreadRunning.get()) {
            try {
                boolean shouldSleep = true;

                // Process the block stream queue, creating a PublishStreamRequest for a particular Block if conditions
                // are met
                processBlockStreamQueue();

                // If signaled to jump to a specific block, do so
                jumpToBlock();

                // Get Block Node connection that is ACTIVE
                final BlockNodeConnection currentActiveConnection = blockStreamStateManager.getActiveConnection();
                if (currentActiveConnection != null) {
                    shouldSleep = processStreamingToBlockNode(currentActiveConnection);
                }

                if (shouldSleep
                        && !blockStreamStateManager.getBlockStreamQueue().isEmpty()) {
                    shouldSleep = false; // Don't sleep if there are items in the queue
                }

                // Sleep for a short duration to avoid busy waiting
                if (shouldSleep) {
                    Thread.sleep(PROCESSOR_LOOP_DELAY_MS);
                }
            } catch (InterruptedException e) {
                logger.error(
                        "[{}] blockStreamWorkerLoop encountered an error: {}",
                        Thread.currentThread().getName(),
                        e.getMessage(),
                        e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error(
                        "[{}] blockStreamWorkerLoop encountered an error: {}",
                        Thread.currentThread().getName(),
                        e.getMessage(),
                        e);
            }
        }
    }

    private boolean processStreamingToBlockNode(BlockNodeConnection currentActiveConnection) {
        final BlockState blockState = blockStreamStateManager.getBlockState(streamingBlockNumber.get());
        final long currentBlockNumber = blockStreamStateManager.getBlockNumber();

        if (blockState == null && currentBlockNumber > streamingBlockNumber.get()) {
            logger.debug(
                    "[{}] Block {} state not found and current block is {}, ending stream for node {}",
                    Thread.currentThread().getName(),
                    streamingBlockNumber.get(),
                    currentBlockNumber,
                    blockStreamStateManager
                                    .getActiveConnection()
                                    .getNodeConfig()
                                    .address() + ":"
                            + blockStreamStateManager
                                    .getActiveConnection()
                                    .getNodeConfig()
                                    .port());
            handleConnectionError(currentActiveConnection, LONGER_RETRY_DELAY);
            return true;
        }

        if (blockState != null && blockState.requestsSize() != 0) {
            if (requestIndex < blockState.requestsSize()) {
                logger.trace(
                        "[{}] Processing block {} for node {}, isComplete: {}, requests: {}, requestIndex: {}",
                        Thread.currentThread().getName(),
                        streamingBlockNumber,
                        currentActiveConnection.getNodeConfig().address() + ":"
                                + currentActiveConnection.getNodeConfig().port(),
                        blockState.requestsCompleted(),
                        blockState.requestsSize(),
                        requestIndex);
                PublishStreamRequest publishStreamRequest = blockState.getRequest(requestIndex);
                currentActiveConnection.sendRequest(publishStreamRequest);
                requestIndex++;
            }

            if (requestIndex >= blockState.requestsSize() && blockState.requestsCompleted()) {
                if (blockStreamStateManager.higherPriorityStarted(currentActiveConnection)) {
                    logger.debug(
                            "[{}] BlockStreamProcessor higher priority block node chosen {}",
                            Thread.currentThread().getName(),
                            blockStreamStateManager
                                            .getActiveConnection()
                                            .getNodeConfig()
                                            .address() + ":"
                                    + blockStreamStateManager
                                            .getActiveConnection()
                                            .getNodeConfig()
                                            .port());
                    streamingBlockNumber.set(blockStreamStateManager.getLowestUnackedBlockNumber());
                    requestIndex = 0;
                    return false;
                } else {
                    logger.trace(
                            "[{}] BlockStreamProcessor incrementing blockNumber to {}",
                            Thread.currentThread().getName(),
                            streamingBlockNumber.get() + 1);
                    streamingBlockNumber.getAndIncrement();
                    requestIndex = 0;
                }
            }

            if (requestIndex <= blockState.requestsSize() && !blockState.requestsCompleted()) {
                return false; // Don't sleep if there are more requests to process
            }
        }

        return blockStreamStateManager.getBlockStreamQueue().isEmpty();
    }

    private void processBlockStreamQueue() {
        if (!blockStreamStateManager.getBlockStreamQueue().isEmpty()) {
            BlockStreamQueueItem blockStreamQueueItem =
                    blockStreamStateManager.getBlockStreamQueue().poll();
            if (blockStreamQueueItem != null) {
                long blockNumber = blockStreamQueueItem.getBlockNumber();
                BlockItem blockItem = blockStreamQueueItem.getBlockItem();
                BlockState blockState = blockStreamStateManager.getBlockState(blockNumber);
                if (blockState != null) {
                    if (blockStreamQueueItem
                            .getBlockStreamQueueItemType()
                            .equals(BlockStreamQueueItemType.PRE_BLOCK_PROOF_ACTION)) {
                        blockState.createRequestFromCurrentItems(getBlockItemBatchSize(), true);
                    } else {
                        blockState.addItem(blockItem);
                        blockState.createRequestFromCurrentItems(getBlockItemBatchSize(), false);
                        if (blockItem.hasBlockProof()) {
                            blockState.createRequestFromCurrentItems(getBlockItemBatchSize(), true);
                            blockState.setRequestsCompleted();
                        }
                    }
                }
            }
        }
    }

    private int getBlockItemBatchSize() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .blockItemBatchSize();
    }

    private void jumpToBlock() {
        // Check if the processor has been signaled to jump to a specific block
        final long targetBlock = jumpTargetBlock.getAndSet(-1); // Check and clear jump signal atomically
        if (targetBlock >= 0) {
            logger.debug(
                    "[{}] BlockStreamProcessor received jump signal to block {}",
                    Thread.currentThread().getName(),
                    targetBlock);
            streamingBlockNumber.set(targetBlock);
            requestIndex = 0; // Reset request index for the new block
        }
    }

    /**
     * * Returns the atomic reference tracking the current streaming block number.
     *
     * @return The block number of the block which is currently being streamed to a block node
     */
    public AtomicLong getStreamingBlockNumber() {
        return streamingBlockNumber;
    }

    /**
     * Find a pending connection with the highest priority greater than the current connection
     *
     * @param blockNodeConnection the current connection to compare with
     * @return the highest priority pending connection, or null if none found
     */
    @VisibleForTesting
    BlockNodeConnection getHighestPriorityPendingConnection(@NonNull final BlockNodeConnection blockNodeConnection) {
        BlockNodeConnection highestPri = null;
        for (BlockNodeConnection connection :
                this.blockStreamStateManager.getConnections().values()) {
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

        return highestPri;
    }

    /**
     * Returns the atomic reference for the block number which can be updated
     * and the block stream worker thread will jump to that block on the next iteration.
     *
     * @return the jump target block number
     */
    public AtomicLong getJumpTargetBlock() {
        return jumpTargetBlock;
    }
}
