// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.THIRTY_SECONDS;
import static java.util.Collections.shuffle;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;
import static org.apache.logging.log4j.Level.DEBUG;
import static org.apache.logging.log4j.Level.ERROR;
import static org.apache.logging.log4j.Level.INFO;
import static org.apache.logging.log4j.Level.TRACE;
import static org.apache.logging.log4j.Level.WARN;

import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.util.LoggingUtilities;
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
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
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
import org.apache.logging.log4j.Level;
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
     * Configuration property: delay before retrying after the EndOfStream rate limit is exceeded.
     */
    private final Duration endOfStreamScheduleDelay;
    /**
     * Tracks retry attempts and last retry time for each block node to maintain
     * proper exponential backoff across connection attempts.
     */
    private final Map<BlockNodeConfig, RetryState> retryStates = new ConcurrentHashMap<>();

    /**
     * A class that holds retry state for a block node connection.
     */
    class RetryState {
        private int retryAttempt = 0;
        private Instant lastRetryTime;

        public int getRetryAttempt() {
            return retryAttempt;
        }

        public void increment() {
            retryAttempt++;
        }

        public void updateRetryTime() {
            final Instant now = Instant.now();
            if (lastRetryTime != null) {
                final Duration timeSinceLastRetry = Duration.between(lastRetryTime, now);
                if (timeSinceLastRetry.compareTo(expBackoffTimeframeReset()) > 0) {
                    // It has been long enough since the last retry, so reset the attempt count
                    retryAttempt = 0;
                    lastRetryTime = now;
                    return;
                }
            }
            lastRetryTime = now;
        }
    }

    /**
     * Configuration property: threshold above which a block acknowledgement is considered high latency.
     */
    private final Duration highLatencyThreshold;
    /**
     * Configuration property: number of consecutive high latency events before considering switching nodes.
     */
    private final int highLatencyEventsBeforeSwitching;

    /**
     * Helper method to remove current instance information for debug logging.
     */
    private void logWithContext(Level level, String message, Object... args) {
        if (logger.isEnabled(level)) {
            message = String.format("%s %s", LoggingUtilities.threadInfo(), message);
            logger.atLevel(level).log(message, args);
        }
    }

    /**
     * Helper method to add current connection information for debug logging.
     */
    private void logWithContext(Level level, String message, BlockNodeConnection connection, Object... args) {
        if (logger.isEnabled(level)) {
            message = String.format("%s %s %s", LoggingUtilities.threadInfo(), connection.toString(), message);
            logger.atLevel(level).log(message, args);
        }
    }

    /**
     * Creates a new BlockNodeConnectionManager with the given configuration from disk.
     * @param configProvider the configuration to use
     * @param blockBufferService the block stream state manager
     * @param blockStreamMetrics the block stream metrics to track
     * @param sharedExecutorService the scheduled executor service used to perform async connection operations (e.g. reconnecting)
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
        this.highLatencyThreshold = blockNodeConnectionConfig.highLatencyThreshold();
        this.highLatencyEventsBeforeSwitching = blockNodeConnectionConfig.highLatencyEventsBeforeSwitching();

        isStreamingEnabled.set(isStreamingEnabled());

        if (isStreamingEnabled.get()) {
            final String blockNodeConnectionConfigPath = blockNodeConnectionFileDir();

            availableBlockNodes = new ArrayList<>(extractBlockNodesConfigurations(blockNodeConnectionConfigPath));
            logWithContext(INFO, "Loaded block node configuration from {}.", blockNodeConnectionConfigPath);
            logWithContext(INFO, "Block node configuration: {}.", availableBlockNodes);
        } else {
            logWithContext(INFO, "Block node streaming is disabled. Will not setup connections to block nodes.");
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
     * @return the timeframe after which the exponential backoff state is reset if no retries have occurred
     */
    private Duration expBackoffTimeframeReset() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .protocolExpBackoffTimeframeReset();
    }

    private Duration maxBackoffDelay() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .maxBackoffDelay();
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
            logWithContext(ERROR, "Failed to read block node configuration from {}.", configPath, e);
            throw new RuntimeException("Failed to read block node configuration from " + configPath, e);
        }
    }

    /**
     * Checks if there is only one block node configured.
     * @return whether there is only one block node configured
     */
    public boolean isOnlyOneBlockNodeConfigured() {
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

        final Duration timeoutDuration = configProvider
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .grpcOverallTimeout();

        final Tls tls = Tls.builder().enabled(false).build();
        final PbjGrpcClientConfig grpcConfig =
                new PbjGrpcClientConfig(timeoutDuration, tls, Optional.of(""), "application/grpc");

        final WebClient webClient = WebClient.builder()
                .baseUri("http://" + nodeConfig.address() + ":" + nodeConfig.port())
                .tls(tls)
                .protocolConfigs(List.of(GrpcClientProtocolConfig.builder()
                        .abortPollTimeExpired(false)
                        .pollWaitTime(timeoutDuration)
                        .build()))
                .connectTimeout(timeoutDuration)
                .build();
        logWithContext(
                DEBUG, "Created BlockStreamPublishServiceClient for {}:{}.", nodeConfig.address(), nodeConfig.port());
        return new BlockStreamPublishServiceClient(new PbjGrpcClient(webClient, grpcConfig), OPTIONS);
    }

    /**
     * Closes a connection and reschedules it with the specified delay.
     * This is the consolidated method for handling connection cleanup and retry logic.
     *
     * @param connection the connection to close and reschedule
     * @param delay the delay before attempting to reconnect
     * @param blockNumber the block number to use once reconnected
     * @param selectNewBlockNode whether to select a new block node to connect to while rescheduled
     */
    public void rescheduleConnection(
            @NonNull final BlockNodeConnection connection,
            @Nullable final Duration delay,
            @Nullable final Long blockNumber,
            final boolean selectNewBlockNode) {
        if (!isStreamingEnabled.get()) {
            return;
        }
        requireNonNull(connection, "connection must not be null");

        logWithContext(DEBUG, "Closing and rescheduling connection for reconnect attempt.", connection);

        // Handle cleanup and rescheduling
        handleConnectionCleanupAndReschedule(connection, delay, blockNumber, selectNewBlockNode);
    }

    /**
     * Common logic for handling connection cleanup and rescheduling after a connection is closed.
     * This centralizes the retry and node selection logic.
     */
    private void handleConnectionCleanupAndReschedule(
            @NonNull final BlockNodeConnection connection,
            @Nullable Duration delay,
            @Nullable final Long blockNumber,
            final boolean selectNewBlockNode) {
        // Remove from connections map and clear active reference
        removeConnectionAndClearActive(connection);

        long delayMs;
        // Get or create the retry attempt for this node
        final RetryState retryState = retryStates.computeIfAbsent(connection.getNodeConfig(), k -> new RetryState());
        int retryAttempt = 0;
        synchronized (retryState) {
            // First update the last retry time and possibly reset the attempt count
            retryState.updateRetryTime();
            retryAttempt = retryState.getRetryAttempt();
            if (delay == null) {
                delayMs = calculateJitteredDelayMs(retryAttempt);
            } else {
                delayMs = delay.toMillis();
            }
            // Increment retry attempt count
            retryState.increment();
        }

        logWithContext(
                DEBUG,
                "Apply exponential backoff and reschedule in {} ms (attempt={}).",
                connection,
                delayMs,
                retryAttempt);

        scheduleConnectionAttempt(connection.getNodeConfig(), Duration.ofMillis(delayMs), blockNumber, false);

        if (!isOnlyOneBlockNodeConfigured() && selectNewBlockNode) {
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
        connections.remove(connection.getNodeConfig(), connection);
        activeConnectionRef.compareAndSet(connection, null);
    }

    /**
     * Schedules a connection attempt (or retry) for the given Block Node connection
     * after the specified delay. Handles adding/removing the connection from the retry map.
     *
     * @param blockNodeConfig the connection to schedule a retry for
     * @param initialDelay the delay before the first attempt in this sequence executes
     * @param blockNumber the block number to use once reconnected
     */
    public void scheduleConnectionAttempt(
            @NonNull final BlockNodeConfig blockNodeConfig,
            @NonNull final Duration initialDelay,
            @Nullable final Long blockNumber) {
        scheduleConnectionAttempt(blockNodeConfig, initialDelay, blockNumber, false);
    }

    private void scheduleConnectionAttempt(
            @NonNull final BlockNodeConfig blockNodeConfig,
            @NonNull final Duration initialDelay,
            @Nullable final Long blockNumber,
            final boolean force) {
        if (!isStreamingEnabled.get()) {
            return;
        }
        requireNonNull(blockNodeConfig);
        requireNonNull(initialDelay);

        final long delayMillis = Math.max(0, initialDelay.toMillis());
        final BlockNodeConnection newConnection = createConnection(blockNodeConfig);

        if (blockNumber == null) {
            logWithContext(
                    DEBUG, "Scheduling reconnection for node in {} ms (force={}).", newConnection, delayMillis, force);
        } else {
            logWithContext(
                    DEBUG,
                    "Scheduling reconnection for node at block {} in {} ms (force={}).",
                    newConnection,
                    blockNumber,
                    delayMillis,
                    force);
        }

        // Schedule the first attempt using the connectionExecutor
        try {
            sharedExecutorService.schedule(
                    new BlockNodeConnectionTask(newConnection, initialDelay, blockNumber, force),
                    delayMillis,
                    TimeUnit.MILLISECONDS);
            logWithContext(DEBUG, "Successfully scheduled reconnection task.", newConnection);
        } catch (final Exception e) {
            logWithContext(WARN, "Failed to schedule connection task for block node.", newConnection, e);
            connections.remove(newConnection.getNodeConfig());
            newConnection.close(true);
        }
    }

    /**
     * Gracefully shuts down the connection manager, closing the active connection.
     */
    public void shutdown() {
        if (!isStreamingEnabled.get()) {
            return;
        }

        // Shutdown the block buffer
        blockBufferService.shutdown();

        logWithContext(INFO, "Shutting down connection manager.");

        if (!isConnectionManagerActive.compareAndSet(true, false)) {
            logWithContext(DEBUG, "Connection Manager already shutdown.");
            return;
        }

        // Stop the block stream worker loop thread
        final Thread workerThread = blockStreamWorkerThreadRef.get();
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                logWithContext(DEBUG, "Interrupted while waiting for block stream worker thread to terminate.", e);
            }
        }
        blockStreamWorkerThreadRef.set(null);

        // Close all connections
        final Iterator<Map.Entry<BlockNodeConfig, BlockNodeConnection>> it =
                connections.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<BlockNodeConfig, BlockNodeConnection> entry = it.next();
            final BlockNodeConnection connection = entry.getValue();
            try {
                connection.close(true);
            } catch (final RuntimeException e) {
                logWithContext(
                        DEBUG,
                        "Error while closing connection during connection manager shutdown. Ignoring.",
                        connection,
                        e);
            }
            it.remove();
        }

        // clear metadata
        streamingBlockNumber.set(-1);
        requestIndex = 0;
        activeConnectionRef.set(null);
        nodeStats.clear();
    }

    /**
     * Starts the connection manager. This will schedule a connection attempt to one of the block nodes. This does not
     * block.
     */
    public void start() {
        logWithContext(DEBUG, "Starting connection manager.");
        if (!isStreamingEnabled.get()) {
            logWithContext(DEBUG, "Cannot start the connection manager, streaming is not enabled.");
            return;
        }

        if (!isConnectionManagerActive.compareAndSet(false, true)) {
            return;
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
        logWithContext(DEBUG, "Selecting highest priority available block node for connection attempt.");
        if (!isStreamingEnabled.get()) {
            logWithContext(DEBUG, "Cannot select block node, streaming is not enabled.");
            return false;
        }

        final BlockNodeConfig selectedNode = getNextPriorityBlockNode();

        if (selectedNode == null) {
            logWithContext(DEBUG, "No available block nodes found for streaming.");
            return false;
        }

        logWithContext(
                DEBUG, "Selected block node {}:{} for connection attempt", selectedNode.address(), selectedNode.port());

        // Immediately schedule the FIRST connection attempt.
        scheduleConnectionAttempt(selectedNode, Duration.ZERO, null, force);

        return true;
    }

    /**
     * Selects the next available block node based on priority.
     * It will skip over any nodes that are already in retry or have a lower priority than the current active connection.
     *
     * @return the next available block node configuration
     */
    private @Nullable BlockNodeConfig getNextPriorityBlockNode() {
        logWithContext(DEBUG, "Searching for new block node connection based on node priorities.");

        final SortedMap<Integer, List<BlockNodeConfig>> priorityGroups = availableBlockNodes.stream()
                .collect(Collectors.groupingBy(BlockNodeConfig::priority, TreeMap::new, Collectors.toList()));

        BlockNodeConfig selectedNode = null;

        for (final Map.Entry<Integer, List<BlockNodeConfig>> entry : priorityGroups.entrySet()) {
            final int priority = entry.getKey();
            final List<BlockNodeConfig> nodesInGroup = entry.getValue();
            selectedNode = findAvailableNode(nodesInGroup);

            if (selectedNode == null) {
                logWithContext(DEBUG, "No available node found in priority group {}.", priority);
            } else {
                logWithContext(DEBUG, "Found available node in priority group {}.", priority);
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

        // Create the connection object with a fresh gRPC client
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
        logWithContext(DEBUG, "Opening block with number {}.", blockNumber);
        if (!isStreamingEnabled.get()) {
            logWithContext(DEBUG, "Cannot open block, streaming is not enabled.");
            return;
        }

        final BlockNodeConnection activeConnection = activeConnectionRef.get();
        if (activeConnection == null) {
            blockStreamMetrics.recordNoActiveConnection();
            logWithContext(DEBUG, "No active connections available for streaming block {}", blockNumber);
            return;
        }

        if (streamingBlockNumber.get() == -1) {
            logWithContext(DEBUG, "Current streaming block number is -1, jumping to {}.", blockNumber);
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
        logWithContext(
                DEBUG,
                "Updating last verified block for {}:{} to {}.",
                blockNodeConfig.address(),
                blockNodeConfig.port(),
                blockNumber);
        if (!isStreamingEnabled.get()) {
            logWithContext(DEBUG, "Cannot update last verified block, streaming is not enabled.");
            return;
        }

        requireNonNull(blockNodeConfig);

        lastVerifiedBlockPerConnection.compute(
                blockNodeConfig,
                (cfg, lastVerifiedBlockNumber) ->
                        lastVerifiedBlockNumber == null ? blockNumber : Math.max(lastVerifiedBlockNumber, blockNumber));
        blockBufferService.setLatestAcknowledgedBlock(blockNumber);
    }

    private void sleep(final Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void blockStreamWorkerLoop() {
        while (isConnectionManagerActive.get()) {
            // use the same connection for all operations per iteration
            final BlockNodeConnection connection = activeConnectionRef.get();

            if (connection == null) {
                sleep(workerLoopSleepDuration());
                continue;
            }

            try {
                // If signaled to jump to a specific block, do so
                jumpToBlockIfNeeded();

                final boolean shouldSleep = processStreamingToBlockNode(connection);

                // Sleep for a short duration to avoid busy waiting
                if (shouldSleep) {
                    sleep(workerLoopSleepDuration());
                }
            } catch (final UncheckedIOException e) {
                logWithContext(DEBUG, "UncheckedIOException caught in block stream worker loop ({}).", e.getMessage());
                connection.handleStreamFailureWithoutOnComplete();
            } catch (final Exception e) {
                logWithContext(DEBUG, "Exception caught in block stream worker loop ({}).", e.getMessage());
                connection.handleStreamFailure();
            }
        }
    }

    /**
     * Send at most one request to the active block node - if there is one.
     *
     * @param connection the connection to use for streaming block data
     * @return true if the worker thread should sleep because of a lack of work to do, else false (the worker thread
     * should NOT sleep)
     */
    private boolean processStreamingToBlockNode(final BlockNodeConnection connection) {
        if (connection == null || ConnectionState.ACTIVE != connection.getConnectionState()) {
            return true;
        }

        final long currentStreamingBlockNumber = streamingBlockNumber.get();
        final BlockState blockState = blockBufferService.getBlockState(currentStreamingBlockNumber);
        final long latestBlockNumber = blockBufferService.getLastBlockNumberProduced();

        if (blockState == null && latestBlockNumber > currentStreamingBlockNumber) {
            logWithContext(
                    DEBUG,
                    "Block {} not found in buffer (latestBlock={}). Closing and rescheduling.",
                    connection,
                    currentStreamingBlockNumber,
                    latestBlockNumber);

            connection.close(true);
            rescheduleConnection(connection, THIRTY_SECONDS, null, true);
            return true;
        }

        if (blockState == null) {
            return true;
        }

        blockState.processPendingItems(blockItemBatchSize());

        if (blockState.numRequestsCreated() == 0) {
            // the block was not found or there are no requests available to send, so return true (safe to sleep)
            logWithContext(DEBUG, "Idle: no requests for block {}.", connection, currentStreamingBlockNumber);
            return true;
        }

        if (requestIndex < blockState.numRequestsCreated()) {
            logWithContext(
                    DEBUG,
                    "Processing block {} (isBlockProofSent={}, totalBlockRequests={}, currentRequestIndex={}).",
                    connection,
                    streamingBlockNumber,
                    blockState.isBlockProofSent(),
                    blockState.numRequestsCreated(),
                    requestIndex);
            final PublishStreamRequest publishStreamRequest = blockState.getRequest(requestIndex);
            if (publishStreamRequest != null) {
                connection.sendRequest(publishStreamRequest);
                logWithContext(
                        TRACE, "Sent request {} for block {}.", connection, requestIndex, currentStreamingBlockNumber);
                blockState.markRequestSent(requestIndex);
                requestIndex++;
            }
        }

        if (requestIndex == blockState.numRequestsCreated() && blockState.isBlockProofSent()) {
            final long nextBlockNumber = streamingBlockNumber.incrementAndGet();
            requestIndex = 0;
            logWithContext(DEBUG, "Moving to next block number: {}.", connection, nextBlockNumber);
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

        logWithContext(DEBUG, "Jumping to block {}.", targetBlock);
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
            logWithContext(DEBUG, "Cannot jump to block, streaming is not enabled.");
            return;
        }

        logWithContext(DEBUG, "Marking request to jump to block {}.", blockNumberToJumpTo);
        jumpTargetBlock.set(blockNumberToJumpTo);
    }

    /**
     * Runnable task to handle the connection attempt logic.
     * Schedules itself for subsequent retries upon failure using the connectionExecutor.
     * Handles setting active connection and signaling on success.
     */
    class BlockNodeConnectionTask implements Runnable {
        private final BlockNodeConnection connection;
        private Duration currentBackoffDelayMs;
        private final Long blockNumber;
        private final boolean force;

        /**
         * Helper method to add current connection information for debug logging.
         */
        private void logWithContext(Level level, String message, Object... args) {
            if (logger.isEnabled(level)) {
                message = String.format("%s %s %s", LoggingUtilities.threadInfo(), connection.toString(), message);
                logger.atLevel(level).log(message, args);
            }
        }

        BlockNodeConnectionTask(
                @NonNull final BlockNodeConnection connection,
                @NonNull final Duration initialDelay,
                @Nullable final Long blockNumber,
                final boolean force) {
            this.connection = requireNonNull(connection);
            // Ensure the initial delay is non-negative for backoff calculation
            this.currentBackoffDelayMs = initialDelay.isNegative() ? Duration.ZERO : initialDelay;
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
                logWithContext(DEBUG, "Cannot run connection task, streaming is not enabled.");
                return;
            }

            if (!isConnectionManagerActive.get()) {
                logWithContext(DEBUG, "Cannot run connection task, connection manager has shutdown.");
                return;
            }

            try {
                logWithContext(DEBUG, "Running connection task.");
                final BlockNodeConnection activeConnection = activeConnectionRef.get();

                if (activeConnection != null) {
                    if (activeConnection.equals(connection)) {
                        // not sure how the active connection is in a connectivity task, ignoring
                        logWithContext(DEBUG, "The current connection is the active connection, ignoring task.");
                        return;
                    } else if (force) {
                        final BlockNodeConfig newConnConfig = connection.getNodeConfig();
                        final BlockNodeConfig oldConnConfig = activeConnection.getNodeConfig();
                        logWithContext(
                                DEBUG,
                                "Promoting forced connection with priority={} over active ({}:{} priority={}).",
                                newConnConfig.priority(),
                                oldConnConfig.address(),
                                oldConnConfig.port(),
                                oldConnConfig.priority());
                    } else if (activeConnection.getNodeConfig().priority()
                            <= connection.getNodeConfig().priority()) {
                        // this new connection has a lower (or equal) priority than the existing active connection
                        // this connection task should thus be cancelled/ignored
                        logWithContext(
                                DEBUG,
                                "Active connection has equal/higher priority. Ignoring candidate. Active: {}.",
                                activeConnection);
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
                    final long blockToJumpTo =
                            blockNumber != null ? blockNumber : blockBufferService.getLastBlockNumberProduced();

                    jumpTargetBlock.set(blockToJumpTo);
                    recordActiveConnectionIp(connection.getNodeConfig());
                    logWithContext(DEBUG, "Jump target block is set to {}.", blockToJumpTo);
                } else {
                    // Another connection task has preempted this task, reschedule and try again
                    logWithContext(DEBUG, "Current connection task was preempted, rescheduling.");
                    reschedule();
                }

                if (activeConnection != null) {
                    // close the old active connection
                    try {
                        logWithContext(DEBUG, "Closing current active connection {}.", activeConnection);
                        activeConnection.close(true);
                    } catch (final RuntimeException e) {
                        logWithContext(
                                DEBUG,
                                "Failed to shutdown current active connection {} (shutdown reason: another connection was elevated to active).",
                                activeConnection,
                                e);
                    }
                }
            } catch (final Exception e) {
                logWithContext(DEBUG, "Failed to establish connection to block node. Will schedule a retry.");
                blockStreamMetrics.recordConnectionCreateFailure();
                reschedule();
            }
        }

        /**
         * Reschedules the connection attempt.
         */
        private void reschedule() {
            // Calculate the next delay based on the *previous* backoff delay for this task instance
            Duration nextDelay = currentBackoffDelayMs.isZero()
                    ? INITIAL_RETRY_DELAY // Start with the initial delay if previous was 0
                    : currentBackoffDelayMs.multipliedBy(RETRY_BACKOFF_MULTIPLIER);

            final Duration maxBackoff = maxBackoffDelay();
            if (nextDelay.compareTo(maxBackoff) > 0) {
                nextDelay = maxBackoff;
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
            this.currentBackoffDelayMs = Duration.ofMillis(jitteredDelayMs);

            // Reschedule this task using the calculated jittered delay
            try {
                sharedExecutorService.schedule(this, jitteredDelayMs, TimeUnit.MILLISECONDS);
                logWithContext(
                        DEBUG,
                        "Rescheduled connection attempt (delayMillis={}, backoff={}).",
                        jitteredDelayMs,
                        currentBackoffDelayMs);
            } catch (final Exception e) {
                logWithContext(DEBUG, "Failed to reschedule connection attempt. Removing from retry map.", e);
                // If rescheduling fails, close the connection and remove it from the connection map. A periodic task
                // will handle checking if there are no longer any connections
                connections.remove(connection.getNodeConfig());
                connection.close(true);
            }
        }
    }

    private long calculateJitteredDelayMs(final int retryAttempt) {
        // Calculate delay using exponential backoff starting from INITIAL_RETRY_DELAY
        Duration nextDelay = INITIAL_RETRY_DELAY.multipliedBy((long) Math.pow(RETRY_BACKOFF_MULTIPLIER, retryAttempt));

        final Duration maxBackoff = maxBackoffDelay();
        if (nextDelay.compareTo(maxBackoff) > 0) {
            nextDelay = maxBackoff;
        }

        // Apply jitter to delay
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        return nextDelay.toMillis() / 2 + random.nextLong(nextDelay.toMillis() / 2 + 1);
    }

    /**
     * Increments the count of EndOfStream responses for the specified block node
     * and then checks if this new count exceeds the configured rate limit.
     *
     * @param blockNodeConfig the configuration for the block node
     * @return true if the rate limit is exceeded, otherwise false
     */
    public boolean recordEndOfStreamAndCheckLimit(
            @NonNull final BlockNodeConfig blockNodeConfig, @NonNull final Instant timestamp) {
        if (!isStreamingEnabled.get()) {
            return false;
        }
        requireNonNull(blockNodeConfig, "blockNodeConfig must not be null");

        final BlockNodeStats stats = nodeStats.computeIfAbsent(blockNodeConfig, k -> new BlockNodeStats());
        return stats.addEndOfStreamAndCheckLimit(timestamp, maxEndOfStreamsAllowed, endOfStreamTimeFrame);
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

    /**
     * Converts the specified IPv4 address into an integer value.
     *
     * @param address the address to convert
     * @return a long that represents the IP address
     * @throws IllegalArgumentException when the specified address is not IPv4
     */
    private static long calculateIpAsInteger(@NonNull final InetAddress address) {
        requireNonNull(address);
        final byte[] bytes = address.getAddress();

        if (bytes.length != 4) {
            throw new IllegalArgumentException("Only IPv4 addresses are supported");
        }

        final long octet1 = 256L * 256 * 256 * (bytes[0] & 0xFF);
        final long octet2 = 256L * 256 * (bytes[1] & 0xFF);
        final long octet3 = 256L * (bytes[2] & 0xFF);
        final long octet4 = 1L * (bytes[3] & 0xFF);
        return octet1 + octet2 + octet3 + octet4;
    }

    private void recordActiveConnectionIp(final BlockNodeConfig nodeConfig) {
        long ipAsInteger;

        // Attempt to resolve the address of the block node
        try {
            final URL blockNodeUrl = URI.create("http://" + nodeConfig.address() + ":" + nodeConfig.port())
                    .toURL();
            final InetAddress blockAddress = InetAddress.getByName(blockNodeUrl.getHost());

            // TODO: Use metric labels to capture active node's IP
            // Once our metrics library supports labels, we will want to re-use the metric below to instead
            // emit a single value, like '1', and include a label called something like 'blockNodeIp' with
            // the
            // value being the resolved block node's IP. Then the Grafana dashboard can be updated to use
            // the
            // label value and show which block node the consensus node is connected to at any given time.
            // It may also be better to have a background task that runs every second or something that
            // continuously emits the metric instead of just when a connection is promoted to active.
            ipAsInteger = calculateIpAsInteger(blockAddress);

            logWithContext(
                    INFO,
                    "Active block node connection updated to: {}:{} (resolvedIp: {}, resolvedIpAsInt={})",
                    nodeConfig.address(),
                    nodeConfig.port(),
                    blockAddress.getHostAddress(),
                    ipAsInteger);
        } catch (final IOException e) {
            logWithContext(
                    ERROR, "Failed to resolve block node host ({}:{})", nodeConfig.address(), nodeConfig.port(), e);
            ipAsInteger = -1L;
        }

        blockStreamMetrics.recordActiveConnectionIp(ipAsInteger);
    }

    /**
     * Records when a block proof was sent to a block node. This enables latency measurement upon acknowledgement.
     *
     * @param blockNodeConfig the target block node configuration
     * @param blockNumber the block number of the sent proof
     * @param timestamp the timestamp when the block was sent
     */
    public void recordBlockProofSent(
            @NonNull final BlockNodeConfig blockNodeConfig, final long blockNumber, @NonNull final Instant timestamp) {
        if (!isStreamingEnabled.get()) {
            return;
        }
        requireNonNull(blockNodeConfig, "blockNodeConfig must not be null");

        final BlockNodeStats stats = nodeStats.computeIfAbsent(blockNodeConfig, k -> new BlockNodeStats());
        stats.recordBlockProofSent(blockNumber, timestamp);
    }

    /**
     * Records a block acknowledgement and evaluates latency for a given block node. Updates metrics and determines
     * whether a switch should be considered due to consecutive high-latency events.
     *
     * @param blockNodeConfig the block node configuration that acknowledged the block
     * @param blockNumber the acknowledged block number
     * @param timestamp the timestamp of the block acknowledgement
     * @return the evaluation result including latency and switching decision
     */
    public BlockNodeStats.HighLatencyResult recordBlockAckAndCheckLatency(
            @NonNull final BlockNodeConfig blockNodeConfig, final long blockNumber, @NonNull final Instant timestamp) {
        if (!isStreamingEnabled.get()) {
            return new BlockNodeStats.HighLatencyResult(0L, 0, false, false);
        }
        requireNonNull(blockNodeConfig, "blockNodeConfig must not be null");

        final BlockNodeStats stats = nodeStats.computeIfAbsent(blockNodeConfig, k -> new BlockNodeStats());
        final BlockNodeStats.HighLatencyResult result = stats.recordAcknowledgementAndEvaluate(
                blockNumber, timestamp, highLatencyThreshold, highLatencyEventsBeforeSwitching);
        final long latencyMs = result.latencyMs();

        // Update metrics
        blockStreamMetrics.recordAcknowledgementLatency(latencyMs);
        if (result.isHighLatency()) {
            logWithContext(
                    DEBUG,
                    "[{}] A high latency event ({}ms) has occurred. A total of {} consecutive events",
                    blockNodeConfig,
                    latencyMs,
                    result.consecutiveHighLatencyEvents());
            blockStreamMetrics.recordHighLatencyEvent();
        }

        return result;
    }
}
