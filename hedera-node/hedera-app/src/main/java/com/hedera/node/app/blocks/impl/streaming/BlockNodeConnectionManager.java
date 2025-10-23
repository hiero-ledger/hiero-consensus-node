// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.util.LoggingUtilities.formatLogMessage;
import static com.hedera.node.app.util.LoggingUtilities.logWithContext;
import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static java.util.Collections.shuffle;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;
import static org.apache.logging.log4j.Level.DEBUG;
import static org.apache.logging.log4j.Level.INFO;

import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection.ConnectionState;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.util.LoggingUtilities;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.node.internal.network.BlockNodeConnectionInfo;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.Level;
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
@Singleton
public class BlockNodeConnectionManager {

    private static final Logger logger = LogManager.getLogger(BlockNodeConnectionManager.class);

    /**
     * Initial retry delay for connection attempts.
     */
    public static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(1);
    /**
     * The multiplier used for exponential backoff when retrying connections.
     */
    private static final long RETRY_BACKOFF_MULTIPLIER = 2;
    /**
     * Manager that maintains the block stream on this consensus node.
     */
    private final BlockBufferService blockBufferService;
    /**
     * Scheduled executor service that is used to schedule asynchronous tasks such as reconnecting to block nodes.
     * It is shared across all connections to block nodes, allowing periodic stream resets.
     */
    private ScheduledExecutorService sharedExecutorService;
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
    private final List<BlockNodeConfig> availableBlockNodes = new ArrayList<>();
    /**
     * Flag that indicates if this connection manager is active or not. In this case, being active means it is actively
     * processing blocks and attempting to send them to a block node.
     */
    private final AtomicBoolean isConnectionManagerActive = new AtomicBoolean(false);
    /**
     * Watch service for monitoring the block node configuration file for updates.
     */
    private final AtomicReference<WatchService> configWatchServiceRef = new AtomicReference<>();
    /**
     * Reference to the configuration watcher thread.
     */
    private final AtomicReference<Thread> configWatcherThreadRef = new AtomicReference<>();
    /**
     * The directory containing the block node connection configuration file.
     */
    private Path blockNodeConfigDirectory;
    /**
     * The file name of the block node configuration file.
     */
    private static final String BLOCK_NODES_FILE_NAME = "block-nodes.json";
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
     * Tracks health and connection history for each block node across multiple connection instances.
     * This data persists beyond individual BlockNodeConnection lifecycles.
     */
    private final Map<BlockNodeConfig, BlockNodeStats> nodeStats;
    /**
     * Tracks retry attempts and last retry time for each block node to maintain
     * proper exponential backoff across connection attempts.
     */
    private final Map<BlockNodeConfig, RetryState> retryStates = new ConcurrentHashMap<>();

    private final BlockNodeClientFactory clientFactory;

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
     * Creates a new BlockNodeConnectionManager with the given configuration from disk.
     * @param configProvider the configuration to use
     * @param blockBufferService the block stream state manager
     * @param blockStreamMetrics the block stream metrics to track
     */
    @Inject
    public BlockNodeConnectionManager(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockBufferService blockBufferService,
            @NonNull final BlockStreamMetrics blockStreamMetrics) {
        this.configProvider = requireNonNull(configProvider, "configProvider must not be null");
        this.blockBufferService = requireNonNull(blockBufferService, "blockBufferService must not be null");
        this.blockStreamMetrics = requireNonNull(blockStreamMetrics, "blockStreamMetrics must not be null");
        this.nodeStats = new ConcurrentHashMap<>();
        this.blockNodeConfigDirectory = getAbsolutePath(blockNodeConnectionFileDir());
        this.clientFactory = new BlockNodeClientFactory();
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
     * Extracts block node configurations from the specified configuration file.
     *
     * @param blockNodeConfigPath the path to the block node configuration file
     * @return the configurations for all block nodes
     */
    private List<BlockNodeConfig> extractBlockNodesConfigurations(@NonNull final String blockNodeConfigPath) {
        final Path configPath = Paths.get(blockNodeConfigPath, BLOCK_NODES_FILE_NAME);
        try {
            if (!Files.exists(configPath)) {
                logWithContext(logger, INFO, "Block node configuration file does not exist: {}", configPath);
                return List.of();
            }

            final byte[] jsonConfig = Files.readAllBytes(configPath);
            final BlockNodeConnectionInfo protoConfig = BlockNodeConnectionInfo.JSON.parse(Bytes.wrap(jsonConfig));

            // Convert proto config to internal config objects
            return protoConfig.nodes().stream()
                    .map(node -> new BlockNodeConfig(node.address(), node.port(), node.priority()))
                    .toList();
        } catch (final IOException | ParseException e) {
            logWithContext(
                    logger,
                    INFO,
                    "Failed to read or parse block node configuration from {}. Continuing without block node connections.",
                    configPath,
                    e);
            return List.of();
        }
    }

    /**
     * Checks if there is only one block node configured.
     * @return whether there is only one block node configured
     */
    public boolean isOnlyOneBlockNodeConfigured() {
        int size;
        synchronized (availableBlockNodes) {
            size = availableBlockNodes.size();
        }
        return size == 1;
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
        if (!isStreamingEnabled()) {
            return;
        }
        requireNonNull(connection, "connection must not be null");

        logWithContext(logger, DEBUG, connection, "Closing and rescheduling connection for reconnect attempt.");

        // Handle cleanup and rescheduling
        handleConnectionCleanupAndReschedule(connection, delay, blockNumber, selectNewBlockNode);
    }

    /**
     * Common logic for handling connection cleanup and rescheduling after a connection is closed.
     * This centralizes the retry and node selection logic.
     */
    private void handleConnectionCleanupAndReschedule(
            @NonNull final BlockNodeConnection connection,
            @Nullable final Duration delay,
            @Nullable final Long blockNumber,
            final boolean selectNewBlockNode) {
        // Remove from connections map and clear active reference
        removeConnectionAndClearActive(connection);

        final long delayMs;
        // Get or create the retry attempt for this node
        final RetryState retryState = retryStates.computeIfAbsent(connection.getNodeConfig(), k -> new RetryState());
        final int retryAttempt;
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
                logger,
                INFO,
                connection,
                "Apply exponential backoff and reschedule in {} ms (attempt={}).",
                delayMs,
                retryAttempt);

        scheduleConnectionAttempt(connection.getNodeConfig(), Duration.ofMillis(delayMs), blockNumber, false);

        if (!isOnlyOneBlockNodeConfigured() && selectNewBlockNode) {
            // Immediately try to find and connect to the next available node
            selectNewBlockNodeForStreaming(false);
        }
    }

    /**
     * Connection initiated a reset of the stream
     * @param connection the connection that initiated the reset of the stream
     */
    public void connectionResetsTheStream(@NonNull final BlockNodeConnection connection) {
        if (!isStreamingEnabled()) {
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

    private void scheduleConnectionAttempt(
            @NonNull final BlockNodeConfig blockNodeConfig,
            @NonNull final Duration initialDelay,
            @Nullable final Long initialBlockToStream,
            final boolean force) {
        if (!isStreamingEnabled()) {
            return;
        }
        requireNonNull(blockNodeConfig);
        requireNonNull(initialDelay);

        final long delayMillis = Math.max(0, initialDelay.toMillis());
        final BlockNodeConnection newConnection = createConnection(blockNodeConfig, initialBlockToStream);

        logWithContext(
                logger,
                DEBUG,
                newConnection,
                "Scheduling reconnection for node in {} ms (force={}).",
                delayMillis,
                force);

        // Schedule the first attempt using the connectionExecutor
        try {
            sharedExecutorService.schedule(
                    new BlockNodeConnectionTask(newConnection, initialDelay, force),
                    delayMillis,
                    TimeUnit.MILLISECONDS);
            logWithContext(logger, DEBUG, "Successfully scheduled reconnection task.", newConnection);
        } catch (final Exception e) {
            logger.error(formatLogMessage("Failed to schedule connection task for block node.", newConnection), e);
            connections.remove(newConnection.getNodeConfig());
            newConnection.close(true);
        }
    }

    /**
     * Gracefully shuts down the connection manager, closing the active connection.
     */
    public void shutdown() {
        if (!isConnectionManagerActive.compareAndSet(true, false)) {
            logWithContext(logger, DEBUG, "Connection Manager already shutdown.");
            return;
        }
        logWithContext(logger, DEBUG, "Shutting down block node connection manager.");

        stopConfigWatcher();
        blockBufferService.shutdown();
        shutdownScheduledExecutorService();
        closeAllConnections();
        clearManagerMetadata();
    }

    private void shutdownScheduledExecutorService() {
        if (sharedExecutorService != null) {
            sharedExecutorService.shutdownNow();
        }
    }

    private void clearManagerMetadata() {
        activeConnectionRef.set(null);
        nodeStats.clear();
        availableBlockNodes.clear();
    }

    private void closeAllConnections() {
        logWithContext(logger, DEBUG, "Stopping block node connections");
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
                        logger,
                        DEBUG,
                        "Error while closing connection during connection manager shutdown. Ignoring.",
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
        if (!isStreamingEnabled()) {
            logWithContext(logger, DEBUG, "Cannot start the connection manager, streaming is not enabled.");
            return;
        }
        if (!isConnectionManagerActive.compareAndSet(false, true)) {
            logWithContext(logger, DEBUG, "Connection Manager already started.");
            return;
        }
        logWithContext(logger, DEBUG, "Starting connection manager.");

        // Start the block buffer service
        blockBufferService.start();

        // Start a watcher to monitor changes to the block-nodes.json file for dynamic updates
        startConfigWatcher();

        refreshAvailableBlockNodes();
    }

    private void createScheduledExecutorService() {
        logWithContext(logger, DEBUG, "Creating scheduled executor service for the Block Node connection manager.");
        sharedExecutorService = Executors.newSingleThreadScheduledExecutor();
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
        if (!isStreamingEnabled()) {
            logWithContext(logger, DEBUG, "Cannot select block node, streaming is not enabled.");
            return false;
        }

        logWithContext(logger, DEBUG, "Selecting highest priority available block node for connection attempt.");

        final BlockNodeConfig selectedNode = getNextPriorityBlockNode();

        if (selectedNode == null) {
            logWithContext(logger, DEBUG, "No available block nodes found for streaming.");
            return false;
        }

        logWithContext(
                logger,
                DEBUG,
                "Selected block node {}:{} for connection attempt",
                selectedNode.address(),
                selectedNode.port());

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
        logWithContext(logger, DEBUG, "Searching for new block node connection based on node priorities.");

        final List<BlockNodeConfig> snapshot;
        synchronized (availableBlockNodes) {
            snapshot = new ArrayList<>(availableBlockNodes);
        }

        final SortedMap<Integer, List<BlockNodeConfig>> priorityGroups = snapshot.stream()
                .collect(Collectors.groupingBy(BlockNodeConfig::priority, TreeMap::new, Collectors.toList()));

        BlockNodeConfig selectedNode = null;

        for (final Map.Entry<Integer, List<BlockNodeConfig>> entry : priorityGroups.entrySet()) {
            final int priority = entry.getKey();
            final List<BlockNodeConfig> nodesInGroup = entry.getValue();
            selectedNode = findAvailableNode(nodesInGroup);

            if (selectedNode == null) {
                logWithContext(logger, DEBUG, "No available node found in priority group {}.", priority);
            } else {
                logWithContext(logger, DEBUG, "Found available node in priority group {}.", priority);
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
    private BlockNodeConnection createConnection(
            @NonNull final BlockNodeConfig nodeConfig, @Nullable final Long initialBlockToStream) {
        requireNonNull(nodeConfig);

        final BlockNodeConnection connection = new BlockNodeConnection(
                configProvider,
                nodeConfig,
                this,
                blockBufferService,
                blockStreamMetrics,
                sharedExecutorService,
                initialBlockToStream,
                clientFactory);

        connections.put(nodeConfig, connection);
        return connection;
    }

    /**
     * Starts a WatchService to monitor the configuration directory for changes to block-nodes.json.
     * On create/modify events, it will attempt to reload configuration and restart connections.
     */
    private void startConfigWatcher() {
        if (configWatchServiceRef.get() != null) {
            logWithContext(logger, DEBUG, "Configuration watcher already running.");
            return;
        }
        try {
            final WatchService watchService =
                    blockNodeConfigDirectory.getFileSystem().newWatchService();
            configWatchServiceRef.set(watchService);
            blockNodeConfigDirectory.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);

            final Thread watcherThread = Thread.ofPlatform()
                    .name("BlockNodesConfigWatcher")
                    .start(() -> {
                        while (!Thread.currentThread().isInterrupted()) {
                            WatchKey key = null;
                            try {
                                key = watchService.take();
                                for (final WatchEvent<?> event : key.pollEvents()) {
                                    final WatchEvent.Kind<?> kind = event.kind();
                                    final Object ctx = event.context();
                                    if (ctx instanceof final Path changed
                                            && BLOCK_NODES_FILE_NAME.equals(changed.toString())) {
                                        logWithContext(logger, INFO, "Detected {} event for {}.", kind.name(), changed);
                                        try {
                                            refreshAvailableBlockNodes();
                                        } catch (Exception e) {
                                            logWithContext(
                                                    logger,
                                                    INFO,
                                                    "Exception in BlockNodesConfigWatcher config file change handler. {}",
                                                    e);
                                        }
                                    }
                                }
                            } catch (final InterruptedException e) {
                                break;
                            } catch (Exception e) {
                                logWithContext(logger, INFO, "Exception in config watcher loop.", e);
                                if (Thread.currentThread().isInterrupted()) {
                                    logWithContext(logger, DEBUG, "Config watcher thread interrupted, exiting.");
                                    return;
                                }
                            } finally {
                                // Always reset the key to continue watching for events, even if an exception occurred
                                if (key != null && !key.reset()) {
                                    logWithContext(
                                            logger, INFO, "WatchKey could not be reset. Exiting config watcher loop.");
                                    break;
                                }
                            }
                        }
                    });
            configWatcherThreadRef.set(watcherThread);
            logWithContext(logger, INFO, "Started block-nodes.json configuration watcher thread.");
        } catch (final IOException e) {
            logger.info("Failed to start block-nodes.json configuration watcher. Dynamic updates disabled.", e);
        }
    }

    /**
     * Stop the configuration file watcher and associated thread.
     */
    private void stopConfigWatcher() {
        final Thread watcherThread = configWatcherThreadRef.getAndSet(null);
        if (watcherThread != null) {
            watcherThread.interrupt();
            try {
                watcherThread.join();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        final WatchService ws = configWatchServiceRef.getAndSet(null);
        if (ws != null) {
            try {
                ws.close();
            } catch (final IOException ignored) {
                // ignore
            }
        }
    }

    private void refreshAvailableBlockNodes() {
        final String configDir = blockNodeConfigDirectory.toString();
        final List<BlockNodeConfig> newConfigs = extractBlockNodesConfigurations(configDir);

        // Compare new configs with existing ones to determine if a restart is needed
        synchronized (availableBlockNodes) {
            if (newConfigs.equals(availableBlockNodes)) {
                logWithContext(logger, INFO, "Block node configuration unchanged. No action taken.");
                return;
            }
        }

        shutdownScheduledExecutorService();
        closeAllConnections();
        clearManagerMetadata();

        synchronized (availableBlockNodes) {
            availableBlockNodes.addAll(newConfigs);
        }

        if (!newConfigs.isEmpty()) {
            logWithContext(logger, INFO, "Reloaded block node configurations ({})", newConfigs);
            createScheduledExecutorService();
            selectNewBlockNodeForStreaming(false);
        } else {
            logWithContext(
                    logger,
                    INFO,
                    "No valid block node configurations available after file change. Connections remain stopped.");
        }
    }

    /**
     * Runnable task to handle the connection attempt logic.
     * Schedules itself for subsequent retries upon failure using the connectionExecutor.
     * Handles setting active connection and signaling on success.
     */
    class BlockNodeConnectionTask implements Runnable {
        private final BlockNodeConnection connection;
        private Duration currentBackoffDelayMs;
        private final boolean force;

        BlockNodeConnectionTask(
                @NonNull final BlockNodeConnection connection,
                @NonNull final Duration initialDelay,
                final boolean force) {
            this.connection = requireNonNull(connection);
            // Ensure the initial delay is non-negative for backoff calculation
            this.currentBackoffDelayMs = initialDelay.isNegative() ? Duration.ZERO : initialDelay;
            this.force = force;
        }

        /**
         * Helper method to add current connection information for debug logging.
         */
        private void logWithContext(final Level level, final String message, final Object... args) {
            if (logger.isEnabled(level)) {
                final String msg =
                        String.format("%s %s %s", LoggingUtilities.threadInfo(), connection.toString(), message);
                logger.atLevel(level).log(msg, args);
            }
        }

        /**
         * Manages the state transitions of gRPC streaming connections to Block Nodes.
         * Connection state transitions are synchronized to ensure thread-safe updates when
         * promoting connections from PENDING to ACTIVE state or handling failures.
         */
        @Override
        public void run() {
            if (!isStreamingEnabled()) {
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
                    recordActiveConnectionIp(connection.getNodeConfig());
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
                        logger.info(
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
                // No-op if node was removed from available list
                synchronized (availableBlockNodes) {
                    if (!availableBlockNodes.contains(connection.getNodeConfig())) {
                        logWithContext(DEBUG, "Node no longer available, skipping reschedule.");
                        connections.remove(connection.getNodeConfig());
                        return;
                    }
                }
                sharedExecutorService.schedule(this, jitteredDelayMs, TimeUnit.MILLISECONDS);
                logWithContext(INFO, "Rescheduled connection attempt (delayMillis={}).", jitteredDelayMs);
            } catch (final Exception e) {
                logger.error("Failed to reschedule connection attempt. Removing from retry map.", e);
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
        if (!isStreamingEnabled()) {
            return false;
        }
        requireNonNull(blockNodeConfig, "blockNodeConfig must not be null");

        final BlockNodeStats stats = nodeStats.computeIfAbsent(blockNodeConfig, k -> new BlockNodeStats());
        return stats.addEndOfStreamAndCheckLimit(timestamp, getMaxEndOfStreamsAllowed(), getEndOfStreamTimeframe());
    }

    /**
     * Gets the configured delay for EndOfStream rate limit violations.
     *
     * @return the delay before retrying after rate limit exceeded
     */
    public Duration getEndOfStreamScheduleDelay() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .endOfStreamScheduleDelay();
    }

    /**
     * Gets the configured timeframe for counting EndOfStream responses.
     *
     * @return the timeframe for rate limiting EndOfStream responses
     */
    public Duration getEndOfStreamTimeframe() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .endOfStreamTimeFrame();
    }

    /**
     * Gets the maximum number of EndOfStream responses allowed before taking corrective action.
     *
     * @return the maximum number of EndOfStream responses permitted
     */
    public int getMaxEndOfStreamsAllowed() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .maxEndOfStreamsAllowed();
    }

    /**
     * Retrieves the total count of EndOfStream responses received from the specified block node.
     *
     * @param blockNodeConfig the configuration for the block node
     * @return the total count of EndOfStream responses
     */
    public int getEndOfStreamCount(@NonNull final BlockNodeConfig blockNodeConfig) {
        if (!isStreamingEnabled()) {
            return 0;
        }
        requireNonNull(blockNodeConfig, "blockNodeConfig must not be null");
        final BlockNodeStats stats = nodeStats.get(blockNodeConfig);
        return stats != null ? stats.getEndOfStreamCount() : 0;
    }

    private Duration getHighLatencyThreshold() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .highLatencyThreshold();
    }

    private int getHighLatencyEventsBeforeSwitching() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .highLatencyEventsBeforeSwitching();
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
                    logger,
                    INFO,
                    "Active block node connection updated to: {}:{} (resolvedIp: {}, resolvedIpAsInt={})",
                    nodeConfig.address(),
                    nodeConfig.port(),
                    blockAddress.getHostAddress(),
                    ipAsInteger);
        } catch (final IOException e) {
            logger.debug("Failed to resolve block node host ({}:{})", nodeConfig.address(), nodeConfig.port(), e);
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
        if (!isStreamingEnabled()) {
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
        if (!isStreamingEnabled()) {
            return new BlockNodeStats.HighLatencyResult(0L, 0, false, false);
        }
        requireNonNull(blockNodeConfig, "blockNodeConfig must not be null");

        final BlockNodeStats stats = nodeStats.computeIfAbsent(blockNodeConfig, k -> new BlockNodeStats());
        final BlockNodeStats.HighLatencyResult result = stats.recordAcknowledgementAndEvaluate(
                blockNumber, timestamp, getHighLatencyThreshold(), getHighLatencyEventsBeforeSwitching());
        final long latencyMs = result.latencyMs();

        // Update metrics
        blockStreamMetrics.recordAcknowledgementLatency(latencyMs);
        if (result.isHighLatency()) {
            logWithContext(
                    logger,
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
