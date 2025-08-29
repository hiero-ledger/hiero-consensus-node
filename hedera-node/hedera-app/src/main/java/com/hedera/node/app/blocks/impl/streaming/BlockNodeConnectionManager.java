// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.LONGER_RETRY_DELAY;
import static java.util.Collections.shuffle;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;

import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.node.internal.network.BlockNodeConnectionInfo;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClient;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClientConfig;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.grpc.ServiceInterface;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.helidon.common.tls.Tls;
import io.helidon.webclient.api.WebClient;
import io.helidon.webclient.grpc.GrpcClientProtocolConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.BlockStreamPublishServiceInterface.BlockStreamPublishServiceClient;
import org.hiero.block.api.PublishStreamRequest;

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
@Singleton
public class BlockNodeConnectionManager {

    private static final Logger logger = LogManager.getLogger(BlockNodeConnectionManager.class);

    private record Options(Optional<String> authority, String contentType) implements ServiceInterface.RequestOptions {}

    private static final BlockNodeConnectionManager.Options OPTIONS =
            new BlockNodeConnectionManager.Options(Optional.empty(), ServiceInterface.RequestOptions.APPLICATION_GRPC);
    /**
     * Initial retry delay for connection attempts.
     */
    public static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(1);
    /**
     * The multiplier used for exponential backoff when retrying connections.
     */
    private static final long RETRY_BACKOFF_MULTIPLIER = 2;
    /**
     * The maximum delay used for reties.
     */
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(10);
    /**
     * Tracks what the last verified block for each connection is. Note: The data maintained here is based on what the
     * block node has informed the consensus node of. If a block node is not actively connected, then this data may be
     * incorrect from the perspective of the block node. It is only when the block node informs the consensus node of
     * its status, then the data will be accurate.
     */
    private final Map<BlockNodeConfig, Long> lastVerifiedBlockPerConnection;
    /**
     * Manager that maintains the block stream on this consensus node.
     */
    private final BlockBufferService blockBufferService;
    /**
     * Scheduled executor service that is used to schedule asynchronous tasks such as reconnecting to block nodes.
     * It is shared across all connections to block nodes, allowing periodic stream resets.
     */
    private final ScheduledExecutorService sharedExecutorService;
    /**
     * Metrics API for block stream-specific metrics.
     */
    private final BlockStreamMetrics blockStreamMetrics;
    /**
     * Mechanism to retrieve configuration properties related to block-node communication.
     */
    private final ConfigProvider configProvider;
    /**
     * List of available block nodes this consensus node can connect to, or at least attempt to. This list is read upon
     * startup from the configuration file(s) on disk.
     */
    private final List<BlockNodeConfig> availableBlockNodes;
    /**
     * Flag that indicates if this connection manager is active or not. In this case, being active means it is actively
     * processing blocks and attempting to send them to a block node.
     */
    private final AtomicBoolean isConnectionManagerActive = new AtomicBoolean(false);
    /**
     * In certain cases, there will be times when we need to jump to a specific block to stream to a block node (e.g.
     * after receiving a SkipBlock or ResendBlock response). When one of these cases arises, this will be updated to
     * indicate which block to jump to upon the next iteration of the worker loop. A value of -1 indicates no jumping
     * is requested.
     */
    private final AtomicLong jumpTargetBlock = new AtomicLong(-1);
    /**
     * This tracks which block is actively being streamed to a block node from this consensus node. A value of -1
     * indicates that no streaming is currently in progress.
     */
    private final AtomicLong streamingBlockNumber = new AtomicLong(-1);
    /**
     * This value represents the index of the request that is being sent to the block node (or was last sent).
     */
    private int requestIndex = 0;
    /**
     * Reference to the worker thread that handles creating requests and sending requests to the connected block node.
     */
    private final AtomicReference<Thread> blockStreamWorkerThreadRef = new AtomicReference<>();
    /**
     * Map that contains one or more connections to block nodes. The connections in this map will be a subset (or all)
     * of the available block node connections. (see {@link BlockNodeConnectionManager#availableBlockNodes})
     */
    private final Map<BlockNodeConfig, BlockNodeConnection> connections = new ConcurrentHashMap<>();
    /**
     * Reference to the currently active connection. If this reference is null, then there is no active connection.
     */
    private final AtomicReference<BlockNodeConnection> activeConnectionRef = new AtomicReference<>();
    /**
     * Flag that indicates if streaming to block nodes is enabled. This flag is set once upon startup and cannot change.
     */
    private final AtomicBoolean isStreamingEnabled = new AtomicBoolean(false);

    /**
     * Tracks health and connection history for each block node across multiple connection instances.
     * This data persists beyond individual BlockNodeConnection lifecycles.
     */
    private final Map<BlockNodeConfig, BlockNodeStats> nodeStats;

    /**
     * Configuration property: the maximum number of EndOfStream responses permitted before taking corrective action.
     */
    private final int maxEndOfStreamsAllowed;
    /**
     * Configuration property: the time window in which EndOfStream responses are counted for rate limiting.
     */
    private final Duration endOfStreamTimeFrame;
    /**
     * Configuration property: delay before retrying after EndOfStream rate limit is exceeded.
     */
    private final Duration endOfStreamScheduleDelay;

    /**
     * Creates a new BlockNodeConnectionManager with the given configuration from disk.
     * @param configProvider the configuration to use
     * @param blockBufferService the block stream state manager
     * @param blockStreamMetrics the block stream metrics to track
     * @param sharedExecutorService the scheduled executor service used to perform async connection operations (e.g. reconnect,)
     */
    @Inject
    public BlockNodeConnectionManager(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockBufferService blockBufferService,
            @NonNull final BlockStreamMetrics blockStreamMetrics,
            @NonNull final ScheduledExecutorService sharedExecutorService) {
        this.configProvider = requireNonNull(configProvider, "configProvider must not be null");
        this.blockBufferService = requireNonNull(blockBufferService, "blockBufferService must not be null");
        this.lastVerifiedBlockPerConnection = new ConcurrentHashMap<>();
        this.blockStreamMetrics = requireNonNull(blockStreamMetrics, "blockStreamMetrics must not be null");
        this.sharedExecutorService = requireNonNull(sharedExecutorService, "sharedExecutorService must not be null");
        this.nodeStats = new ConcurrentHashMap<>();
        final var blockNodeConnectionConfig =
                configProvider.getConfiguration().getConfigData(BlockNodeConnectionConfig.class);
        this.maxEndOfStreamsAllowed = blockNodeConnectionConfig.maxEndOfStreamsAllowed();
        this.endOfStreamTimeFrame = blockNodeConnectionConfig.endOfStreamTimeFrame();
        this.endOfStreamScheduleDelay = blockNodeConnectionConfig.endOfStreamScheduleDelay();

        isStreamingEnabled.set(isStreamingEnabled());

        if (isStreamingEnabled.get()) {
            final String blockNodeConnectionConfigPath = blockNodeConnectionFileDir();

            availableBlockNodes = new ArrayList<>(extractBlockNodesConfigurations(blockNodeConnectionConfigPath));
            logger.info("Loaded block node configuration from {}", blockNodeConnectionConfigPath);
            logger.info("Block node configuration: {}", availableBlockNodes);
        } else {
            logger.info("Block node streaming is disabled; will not setup connections to block nodes");
            availableBlockNodes = new ArrayList<>();
        }
    }

    /**
     * @return true if block node streaming is enabled, else false
     */
    private boolean isStreamingEnabled() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .streamToBlockNodes();
    }

    /**
     * @return the configuration path (as a String) for the block node connections
     */
    private String blockNodeConnectionFileDir() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .blockNodeConnectionFileDir();
    }

    /**
     * @return the batch size for a request to send to the block node
     */
    private int blockItemBatchSize() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .blockItemBatchSize();
    }

    /**
     * The amount of time the worker thread will sleep when there is no work available to process.
     *
     * @return the sleep duration of the worker loop
     */
    private Duration workerLoopSleepDuration() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .workerLoopSleepDuration();
    }

    /**
     * Extracts block node configurations from the specified configuration file.
     *
     * @param blockNodeConfigPath the path to the block node configuration file
     * @return the configurations for all block nodes
     */
    private List<BlockNodeConfig> extractBlockNodesConfigurations(@NonNull final String blockNodeConfigPath) {
        final Path configPath = Paths.get(blockNodeConfigPath, "block-nodes.json");
        try {
            final byte[] jsonConfig = Files.readAllBytes(configPath);
            final BlockNodeConnectionInfo protoConfig = BlockNodeConnectionInfo.JSON.parse(Bytes.wrap(jsonConfig));

            // Convert proto config to internal config objects
            return protoConfig.nodes().stream()
                    .map(node -> new BlockNodeConfig(node.address(), node.port(), node.priority()))
                    .toList();
        } catch (final IOException | ParseException e) {
            logger.error("Failed to read block node configuration from {}", configPath, e);
            throw new RuntimeException("Failed to read block node configuration from " + configPath, e);
        }
    }

    private boolean isOnlyOneBlockNodeConfigured() {
        return availableBlockNodes.size() == 1;
    }

    /**
     * Creates a new gRPC client based on the specified configuration.
     *
     * @param nodeConfig the configuration to use for a specific block node to connect to
     * @return a gRPC client
     */
    private @NonNull BlockStreamPublishServiceClient createNewGrpcClient(@NonNull final BlockNodeConfig nodeConfig) {
        requireNonNull(nodeConfig);

        final Tls tls = Tls.builder().enabled(false).build();
        final PbjGrpcClientConfig grpcConfig =
                new PbjGrpcClientConfig(Duration.ofSeconds(30), tls, Optional.of(""), "application/grpc");

        final WebClient webClient = WebClient.builder()
                .baseUri("http://" + nodeConfig.address() + ":" + nodeConfig.port())
                .tls(tls)
                .protocolConfigs(List.of(GrpcClientProtocolConfig.builder()
                        .abortPollTimeExpired(false)
                        .pollWaitTime(Duration.ofSeconds(30))
                        .build()))
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        return new BlockStreamPublishServiceClient(new PbjGrpcClient(webClient, grpcConfig), OPTIONS);
    }

    /**
     * Closes a connection and reschedules it with the specified delay.
     * This is the consolidated method for handling connection cleanup and retry logic.
     *
     * @param connection the connection to close and reschedule
     * @param delay the delay before attempting to reconnect
     */
    public void rescheduleConnection(@NonNull final BlockNodeConnection connection, @NonNull final Duration delay) {
        if (!isStreamingEnabled.get()) {
            return;
        }

        requireNonNull(connection, "connection must not be null");
        requireNonNull(delay, "delay must not be null");

        logger.warn("[{}] Closing and rescheduling connection for reconnect attempt", connection);

        // Handle cleanup and rescheduling
        handleConnectionCleanupAndReschedule(connection, delay);
    }

    /**
     * Schedules an immediate restart of the connection at the specified block number.
     * This method handles immediate restart scenarios with minimal delay.
     *
     * @param connection the connection to close and restart
     * @param blockNumber the block number to restart at
     */
    public void restartConnection(@NonNull final BlockNodeConnection connection, final long blockNumber) {
        requireNonNull(connection, "connection must not be null");

        // Remove from connections map and clear active reference
        removeConnectionAndClearActive(connection);

        logger.debug("[{}] Immediately scheduling connection at block {}", connection, blockNumber);

        // Schedule restart at the specific block
        scheduleConnectionAttempt(connection, Duration.ZERO, blockNumber, false);
    }

    /**
     * Common logic for handling connection cleanup and rescheduling after a connection is closed.
     * This centralizes the retry and node selection logic.
     */
    private void handleConnectionCleanupAndReschedule(
            @NonNull final BlockNodeConnection connection, @NonNull final Duration delay) {
        // Remove from connections map and clear active reference
        removeConnectionAndClearActive(connection);

        if (isOnlyOneBlockNodeConfigured()) {
            // If there is only one block node configured, we will not try to select a new node
            // Schedule a retry for the failed connection with no delay
            scheduleConnectionAttempt(connection, Duration.ofSeconds(0), null, false);
        } else {
            // Schedule retry for the failed connection after a delay
            scheduleConnectionAttempt(connection, delay, null, false);
            // Immediately try to find and connect to the next available node
            selectNewBlockNodeForStreaming(false);
        }
    }

    /**
     * Connection initiated a periodic reset of the stream
     * @param connection the connection that initiated the reset of the stream
     */
    public void connectionResetsTheStream(@NonNull final BlockNodeConnection connection) {
        if (!isStreamingEnabled.get()) {
            return;
        }
        requireNonNull(connection);

        removeConnectionAndClearActive(connection);

        // Immediately try to find and connect to the next available node
        selectNewBlockNodeForStreaming(false);
    }

    /**
     * Removes a connection from the connections map and clears the active reference if this was the active connection.
     * This is a utility method to ensure consistent cleanup behavior.
     *
     * @param connection the connection to remove and clean up
     */
    private void removeConnectionAndClearActive(@NonNull final BlockNodeConnection connection) {
        requireNonNull(connection);
        connections.remove(connection.getNodeConfig());
        activeConnectionRef.compareAndSet(connection, null);
    }

    /**
     * Schedules a connection attempt (or retry) for the given Block Node connection
     * after the specified delay. Handles adding/removing the connection from the retry map.
     *
     * @param connection the connection to schedule a retry for
     * @param initialDelay the delay before the first attempt in this sequence executes
     * @param blockNumber the block number to use once reconnected
     */
    public void scheduleConnectionAttempt(
            @NonNull final BlockNodeConnection connection,
            @NonNull final Duration initialDelay,
            @Nullable final Long blockNumber) {
        scheduleConnectionAttempt(connection, initialDelay, blockNumber, false);
    }

    private void scheduleConnectionAttempt(
            @NonNull final BlockNodeConnection connection,
            @NonNull final Duration initialDelay,
            @Nullable final Long blockNumber,
            final boolean force) {
        if (!isStreamingEnabled.get()) {
            return;
        }

        requireNonNull(connection);
        requireNonNull(initialDelay);
        final long delayMillis = Math.max(0, initialDelay.toMillis());

        if (blockNumber == null) {
            logger.debug("[{}] Scheduling reconnection for node in {} ms", connection, delayMillis);
        } else {
            logger.debug(
                    "[{}] Scheduling reconnection for node at block {} in {} ms", connection, blockNumber, delayMillis);
        }

        final BlockNodeConnection newConnection = createConnection(connection.getNodeConfig());

        // Schedule the first attempt using the connectionExecutor
        try {
            sharedExecutorService.schedule(
                    new BlockNodeConnectionTask(newConnection, initialDelay, blockNumber, force),
                    delayMillis,
                    TimeUnit.MILLISECONDS);
            logger.debug("[{}] Successfully scheduled reconnection task", connection);
        } catch (final Exception e) {
            logger.error("[{}] Failed to schedule connection task for block node", connection, e);
            // Consider closing the connection object if scheduling fails
            connection.close(true);
        }
    }

    /**
     * Gracefully shuts down the connection manager, closing active connection.
     */
    public void shutdown() {
        if (!isStreamingEnabled.get()) {
            return;
        }

        logger.info("Shutting down connection manager!");
        // Stop the block stream worker loop thread
        isConnectionManagerActive.set(false);
        final Thread workerThread = blockStreamWorkerThreadRef.get();
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted while waiting for block stream worker thread to terminate", e);
            }
        }

        // Close all of the connections
        final Iterator<Map.Entry<BlockNodeConfig, BlockNodeConnection>> it =
                connections.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<BlockNodeConfig, BlockNodeConnection> entry = it.next();
            final BlockNodeConnection connection = entry.getValue();
            try {
                connection.close(true);
            } catch (final RuntimeException e) {
                logger.debug(
                        "[{}] Error while closing connection during connection manager shutdown; ignoring",
                        connection,
                        e);
            }
            it.remove();
        }
    }

    /**
     * Starts the connection manager. This will schedule a connection attempt to one of the block nodes. This does not
     * block.
     */
    public void start() {
        if (!isStreamingEnabled.get()) {
            return;
        }

        if (!isConnectionManagerActive.compareAndSet(false, true)) {
            throw new IllegalStateException("Connection manager already started");
        }

        // start worker thread
        final Thread t = Thread.ofPlatform().name("BlockStreamWorkerLoop").start(this::blockStreamWorkerLoop);
        blockStreamWorkerThreadRef.set(t);

        if (!selectNewBlockNodeForStreaming(false)) {
            isConnectionManagerActive.set(false);
            throw new NoBlockNodesAvailableException();
        }
    }

    /**
     * Selects the next highest priority available block node and schedules a connection attempt.
     *
     * @param force if true then the new connection will take precedence over the current active connection regardless
     *              of priority; if false then connection priority will be used to determine if it is OK to connect to
     *              a different block node
     * @return true if a connection attempt will be made to a node, else false (i.e. no available nodes to connect)
     */
    public boolean selectNewBlockNodeForStreaming(final boolean force) {
        if (!isStreamingEnabled.get()) {
            return false;
        }

        final BlockNodeConfig selectedNode = getNextPriorityBlockNode();

        if (selectedNode == null) {
            logger.debug("No block nodes found for attempted streaming");
            return false;
        }

        logger.debug("Selected block node {}:{} for connection attempt", selectedNode.address(), selectedNode.port());
        // If we selected a node, schedule the connection attempt.
        final BlockNodeConnection connection = createConnection(selectedNode);

        // Immediately schedule the FIRST connection attempt.
        scheduleConnectionAttempt(connection, Duration.ZERO, null, force);

        return true;
    }

    /**
     * Selects the next available block node based on priority.
     * It will skip over any nodes that are already in retry or have a lower priority than the current active connection.
     *
     * @return the next available block node configuration
     */
    private @Nullable BlockNodeConfig getNextPriorityBlockNode() {
        logger.debug("Searching for new block node connection based on node priorities...");

        final SortedMap<Integer, List<BlockNodeConfig>> priorityGroups = availableBlockNodes.stream()
                .collect(Collectors.groupingBy(BlockNodeConfig::priority, TreeMap::new, Collectors.toList()));

        BlockNodeConfig selectedNode = null;

        for (final Map.Entry<Integer, List<BlockNodeConfig>> entry : priorityGroups.entrySet()) {
            final int priority = entry.getKey();
            final List<BlockNodeConfig> nodesInGroup = entry.getValue();
            selectedNode = findAvailableNode(nodesInGroup);

            if (selectedNode == null) {
                logger.trace("No available node found in priority group {}", priority);
            } else {
                logger.trace("Found available node in priority group {}", priority);
                return selectedNode;
            }
        }

        return selectedNode;
    }

    /**
     * Given a list of available nodes, find a node that can be used for creating a new connection.
     * This ensures we always create fresh BlockNodeConnection instances for new pipelines.
     *
     * @param nodes list of possible nodes to connect to
     * @return a node that is a candidate to connect to, or null if no candidate was found
     */
    private @Nullable BlockNodeConfig findAvailableNode(@NonNull final List<BlockNodeConfig> nodes) {
        requireNonNull(nodes, "nodes must not be null");
        // Only allow the selection of nodes which are not currently in the connections map
        return nodes.stream()
                .filter(nodeConfig -> !connections.containsKey(nodeConfig))
                .collect(collectingAndThen(toList(), collected -> {
                    // Randomize the available nodes
                    shuffle(collected);
                    return collected.stream();
                }))
                .findFirst() // select a node
                .orElse(null);
    }

    /**
     * Creates a BlockNodeConnection instance and immediately schedules the *first*
     * connection attempt using the retry mechanism (with zero initial delay).
     * Always creates a new instance to ensure proper Pipeline lifecycle management.
     *
     * @param nodeConfig the configuration of the node to connect to.
     */
    @NonNull
    private BlockNodeConnection createConnection(@NonNull final BlockNodeConfig nodeConfig) {
        requireNonNull(nodeConfig);

        // Create the connection object with fresh gRPC client
        final BlockStreamPublishServiceClient grpcClient = createNewGrpcClient(nodeConfig);
        final BlockNodeConnection connection = new BlockNodeConnection(
                configProvider,
                nodeConfig,
                this,
                blockBufferService,
                grpcClient,
                blockStreamMetrics,
                sharedExecutorService);

        connections.put(nodeConfig, connection);
        return connection;
    }

    /**
     * Opens a block for streaming by setting the target block number.
     * If the connection is already active, it will set the jump target block if the current block number is -1.
     *
     * @param blockNumber the block number to open
     */
    public void openBlock(final long blockNumber) {
        if (!isStreamingEnabled.get()) {
            return;
        }

        final BlockNodeConnection activeConnection = activeConnectionRef.get();
        if (activeConnection == null) {
            logger.debug("No active connections available for streaming block {}", blockNumber);
            return;
        }

        if (streamingBlockNumber.get() == -1) {
            jumpTargetBlock.set(blockNumber);
        }
    }

    /**
     * Updates the last verified block number for a specific block node.
     *
     * @param blockNodeConfig the configuration for the block node
     * @param blockNumber the block number of the last verified block
     */
    public void updateLastVerifiedBlock(@NonNull final BlockNodeConfig blockNodeConfig, final long blockNumber) {
        if (!isStreamingEnabled.get()) {
            return;
        }

        requireNonNull(blockNodeConfig);

        lastVerifiedBlockPerConnection.compute(
                blockNodeConfig,
                (cfg, lastVerifiedBlockNumber) ->
                        lastVerifiedBlockNumber == null ? blockNumber : Math.max(lastVerifiedBlockNumber, blockNumber));
        blockBufferService.setLatestAcknowledgedBlock(blockNumber);
    }

    private void blockStreamWorkerLoop() {
        while (isConnectionManagerActive.get()) {
            try {
                // If signaled to jump to a specific block, do so
                jumpToBlockIfNeeded();

                final boolean shouldSleep = processStreamingToBlockNode();

                // Sleep for a short duration to avoid busy waiting
                if (shouldSleep) {
                    Thread.sleep(workerLoopSleepDuration());
                }
            } catch (final InterruptedException e) {
                logger.error("Block stream worker interrupted", e);
                Thread.currentThread().interrupt();
            } catch (final UncheckedIOException e) {
                logger.debug("UncheckedIOException caught in block stream worker loop {}", e.getMessage());
                final BlockNodeConnection activeConnection = activeConnectionRef.get();
                if (activeConnection != null) {
                    activeConnection.handleStreamFailureWithoutOnComplete();
                }
            } catch (final Exception e) {
                logger.debug("Exception caught in block stream worker loop {}", e.getMessage());
                final BlockNodeConnection activeConnection = activeConnectionRef.get();
                if (activeConnection != null) {
                    activeConnection.handleStreamFailure();
                }
            }
        }
    }

    /**
     * Send at most one request to the active block node - if there is one.
     *
     * @return true if the worker thread should sleep because of a lack of work to do, else false (the worker thread
     * should NOT sleep)
     */
    private boolean processStreamingToBlockNode() {
        final BlockNodeConnection connection = activeConnectionRef.get();
        if (connection == null) {
            return true;
        }

        final long currentStreamingBlockNumber = streamingBlockNumber.get();
        final BlockState blockState = blockBufferService.getBlockState(currentStreamingBlockNumber);
        final long latestBlockNumber = blockBufferService.getLastBlockNumberProduced();

        if (blockState == null && latestBlockNumber > currentStreamingBlockNumber) {
            logger.debug(
                    "[{}] Block {} not found in buffer (latestBlock={}); connection will be closed",
                    connection,
                    currentStreamingBlockNumber,
                    latestBlockNumber);

            connection.close(true);
            rescheduleConnection(connection, LONGER_RETRY_DELAY);
            return true;
        }

        if (blockState == null) {
            return true;
        }

        blockState.processPendingItems(blockItemBatchSize());

        if (blockState.numRequestsCreated() == 0) {
            // the block was not found or there are no requests available to send, so return true (safe to sleep)
            return true;
        }

        if (requestIndex < blockState.numRequestsCreated()) {
            logger.debug(
                    "[{}] Processing block {} (isBlockProofSent={}, totalBlockRequests={}, currentRequestIndex={})",
                    connection,
                    streamingBlockNumber,
                    blockState.isBlockProofSent(),
                    blockState.numRequestsCreated(),
                    requestIndex);
            final PublishStreamRequest publishStreamRequest = blockState.getRequest(requestIndex);
            if (publishStreamRequest != null) {
                connection.sendRequest(publishStreamRequest);
                blockState.markRequestSent(requestIndex);
                requestIndex++;
            }
        }

        if (requestIndex == blockState.numRequestsCreated() && blockState.isBlockProofSent()) {
            final long nextBlockNumber = streamingBlockNumber.incrementAndGet();
            requestIndex = 0;
            logger.trace("[{}] Moving to next block number: {}", connection, nextBlockNumber);
            // we've moved to another block, don't sleep and instead immediately check if there is anything to send
            return false;
        }

        return requestIndex >= blockState.numRequestsCreated(); // Don't sleep if there are more requests to process
    }

    /**
     * Updates the current connection processor to jump to a specific block, if the jump flag is set.
     */
    private void jumpToBlockIfNeeded() {
        // Check if the processor has been signaled to jump to a specific block
        final long targetBlock = jumpTargetBlock.getAndSet(-1); // Check and clear jump signal atomically

        if (targetBlock < 0) {
            // there is nothing to jump to
            return;
        }

        logger.debug("Jumping to block {}", targetBlock);
        streamingBlockNumber.set(targetBlock);
        requestIndex = 0; // Reset request index for the new block
    }

    /**
     * Returns the block number that is currently being streamed
     *
     * @return the number of the block which is currently being streamed to a block node
     */
    public long currentStreamingBlockNumber() {
        return streamingBlockNumber.get();
    }

    /**
     * Set the flag to indicate the current active connection should "jump" to the specified block.
     *
     * @param blockNumberToJumpTo the block number to jump to
     */
    public void jumpToBlock(final long blockNumberToJumpTo) {
        if (!isStreamingEnabled.get()) {
            return;
        }

        logger.debug("Marking request to jump to block {}", blockNumberToJumpTo);
        jumpTargetBlock.set(blockNumberToJumpTo);
    }

    /**
     * Runnable task to handle the connection attempt logic.
     * Schedules itself for subsequent retries upon failure using the connectionExecutor.
     * Handles setting active connection and signaling on success.
     */
    class BlockNodeConnectionTask implements Runnable {
        private final BlockNodeConnection connection;
        private Duration currentBackoffDelay; // Represents the delay *before* the next attempt
        private final Long blockNumber; // If becoming ACTIVE, the blockNumber to jump to
        private final boolean force;

        BlockNodeConnectionTask(
                @NonNull final BlockNodeConnection connection,
                @NonNull final Duration initialDelay,
                @Nullable final Long blockNumber,
                final boolean force) {
            this.connection = requireNonNull(connection);
            // Ensure initial delay is non-negative for backoff calculation
            this.currentBackoffDelay = initialDelay.isNegative() ? Duration.ZERO : initialDelay;
            this.blockNumber = blockNumber;
            this.force = force;
        }

        /**
         * Manages the state transitions of gRPC streaming connections to Block Nodes.
         * Connection state transitions are synchronized to ensure thread-safe updates when
         * promoting connections from PENDING to ACTIVE state or handling failures.
         */
        @Override
        public void run() {
            if (!isStreamingEnabled.get()) {
                return;
            }

            if (!isConnectionManagerActive.get()) {
                logger.info("Connection task will not run because the connection manager has shutdown");
                return;
            }

            try {
                logger.debug("[{}] Running connection task...", connection);
                final BlockNodeConnection activeConnection = activeConnectionRef.get();

                if (activeConnection != null) {
                    if (activeConnection.equals(connection)) {
                        // not sure how the active connection is in a connectivity task... ignoring
                        return;
                    } else if (force) {
                        final BlockNodeConfig newConnConfig = connection.getNodeConfig();
                        final BlockNodeConfig oldConnConfig = activeConnection.getNodeConfig();
                        logger.debug(
                                "New connection ({}:{} priority={}) is being forced as the new connection (old: {}:{} priority={})",
                                newConnConfig.address(),
                                newConnConfig.port(),
                                newConnConfig.priority(),
                                oldConnConfig.address(),
                                oldConnConfig.port(),
                                oldConnConfig.priority());
                    } else if (activeConnection.getNodeConfig().priority()
                            <= connection.getNodeConfig().priority()) {
                        // this new connection has a lower (or equal) priority than the existing active connection
                        // this connection task should thus be cancelled/ignored
                        logger.debug(
                                "The existing active connection ({}) has an equal or higher priority than the "
                                        + "connection ({}) we are attempting to connect to and this new connection attempt will be ignored",
                                activeConnection,
                                connection);

                        connection.close(true);
                        return;
                    }
                }

                /*
                If we have got to this point, it means there is no active connection, or it means there is an active
                connection, but the active connection has a lower priority than the connection in this task. In either
                case, we want to elevate this connection to be the new active connection.
                 */

                connection.createRequestPipeline();

                if (activeConnectionRef.compareAndSet(activeConnection, connection)) {
                    // we were able to elevate this connection to the new active one
                    connection.updateConnectionState(ConnectionState.ACTIVE);
                    final long blockToJumpTo = blockNumber != null
                            ? blockNumber
                            : (blockBufferService.getLowestUnackedBlockNumber() == -1
                                    ? 0
                                    : blockBufferService.getLowestUnackedBlockNumber());

                    jumpTargetBlock.set(blockToJumpTo);
                } else {
                    // Another connection task has preempted this task... reschedule and try again
                    reschedule();
                }

                if (activeConnection != null) {
                    // close the old active connection
                    try {
                        activeConnection.close(true);
                    } catch (final RuntimeException e) {
                        logger.debug(
                                "[{}] Failed to shutdown connection (shutdown reason: another connection was elevated to active)",
                                activeConnection,
                                e);
                    }
                }
            } catch (final Exception e) {
                logger.debug("[{}] Failed to establish connection to block node; will schedule a retry", connection);
                reschedule();
            }
        }

        /**
         * Reschedules the connect attempt.
         */
        private void reschedule() {
            // Calculate next delay based on the *previous* backoff delay for this task instance
            Duration nextDelay = currentBackoffDelay.isZero()
                    ? INITIAL_RETRY_DELAY // Start with initial delay if previous was 0
                    : currentBackoffDelay.multipliedBy(RETRY_BACKOFF_MULTIPLIER);

            if (nextDelay.compareTo(MAX_RETRY_DELAY) > 0) {
                nextDelay = MAX_RETRY_DELAY;
            }

            // Apply jitter
            long jitteredDelayMs;
            final ThreadLocalRandom random = ThreadLocalRandom.current();

            if (nextDelay.toMillis() > 0) {
                jitteredDelayMs = nextDelay.toMillis() / 2 + random.nextLong(nextDelay.toMillis() / 2 + 1);
            } else {
                // Should not happen if INITIAL_RETRY_DELAY > 0, but handle defensively
                jitteredDelayMs =
                        INITIAL_RETRY_DELAY.toMillis() / 2 + random.nextLong(INITIAL_RETRY_DELAY.toMillis() / 2 + 1);
                jitteredDelayMs = Math.max(1, jitteredDelayMs); // Ensure positive delay
            }

            // Update backoff delay *for the next run* of this task instance
            this.currentBackoffDelay = Duration.ofMillis(jitteredDelayMs);

            // Reschedule this task using the calculated jittered delay
            try {
                sharedExecutorService.schedule(this, jitteredDelayMs, TimeUnit.MILLISECONDS);
                logger.debug("[{}] Rescheduled connection attempt (delayMillis={})", connection, jitteredDelayMs);
            } catch (final Exception e) {
                logger.error("[{}] Failed to reschedule connection attempt; removing from retry map", connection, e);
                // If rescheduling fails, close the connection and remove it from the connection map. A periodic task
                // will handle checking if there are no longer any connections
                connections.remove(connection.getNodeConfig());
                connection.close(true);
            }
        }
    }

    /**
     * Increments the count of EndOfStream responses for the specified block node
     * and then checks if this new count exceeds the configured rate limit.
     *
     * @param blockNodeConfig the configuration for the block node
     * @return true if the rate limit is exceeded, otherwise false
     */
    public boolean recordEndOfStreamAndCheckLimit(@NonNull final BlockNodeConfig blockNodeConfig) {
        if (!isStreamingEnabled.get()) {
            return false;
        }
        requireNonNull(blockNodeConfig, "blockNodeConfig must not be null");

        final Instant now = Instant.now();
        final BlockNodeStats stats = nodeStats.computeIfAbsent(blockNodeConfig, k -> new BlockNodeStats());

        return stats.addEndOfStreamAndCheckLimit(now, maxEndOfStreamsAllowed, endOfStreamTimeFrame);
    }

    /**
     * Gets the configured delay for EndOfStream rate limit violations.
     *
     * @return the delay before retrying after rate limit exceeded
     */
    public Duration getEndOfStreamScheduleDelay() {
        return endOfStreamScheduleDelay;
    }

    /**
     * Gets the configured timeframe for counting EndOfStream responses.
     *
     * @return the timeframe for rate limiting EndOfStream responses
     */
    public Duration getEndOfStreamTimeframe() {
        return endOfStreamTimeFrame;
    }

    /**
     * Gets the maximum number of EndOfStream responses allowed before taking corrective action.
     *
     * @return the maximum number of EndOfStream responses permitted
     */
    public int getMaxEndOfStreamsAllowed() {
        return maxEndOfStreamsAllowed;
    }

    /**
     * Retrieves the total count of EndOfStream responses received from the specified block node.
     *
     * @param blockNodeConfig the configuration for the block node
     * @return the total count of EndOfStream responses
     */
    public int getEndOfStreamCount(@NonNull final BlockNodeConfig blockNodeConfig) {
        if (!isStreamingEnabled.get()) {
            return 0;
        }
        requireNonNull(blockNodeConfig, "blockNodeConfig must not be null");
        final BlockNodeStats stats = nodeStats.get(blockNodeConfig);
        return stats != null ? stats.getEndOfStreamCount() : 0;
    }
}
