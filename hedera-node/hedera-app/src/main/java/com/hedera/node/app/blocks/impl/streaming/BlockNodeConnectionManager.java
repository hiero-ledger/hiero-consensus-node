// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
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
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.ClosedWatchServiceException;
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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
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
    private final List<BlockNodeConfiguration> availableBlockNodes = new ArrayList<>();
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
    private final Map<BlockNodeConfiguration, BlockNodeStreamingConnection> connections = new ConcurrentHashMap<>();
    /**
     * Reference to the currently active connection. If this reference is null, then there is no active connection.
     */
    private final AtomicReference<BlockNodeStreamingConnection> activeConnectionRef = new AtomicReference<>();
    /**
     * Tracks health and connection history for each block node across multiple connection instances.
     * This data persists beyond individual BlockNodeConnection lifecycles.
     */
    private final Map<BlockNodeConfiguration, BlockNodeStats> nodeStats;
    /**
     * Tracks retry attempts and last retry time for each block node to maintain
     * proper exponential backoff across connection attempts.
     */
    private final Map<BlockNodeConfiguration, RetryState> retryStates = new ConcurrentHashMap<>();

    private final BlockNodeClientFactory clientFactory;
    /**
     * Supplier for getting instances of an executor service to use for blocking I/O operations.
     */
    private final Supplier<ExecutorService> blockingIoExecutorSupplier;
    /**
     * Executor service used to execute blocking I/O operations - e.g. retrieving block node status.
     */
    private ExecutorService blockingIoExecutor;

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
     * @param blockingIoExecutorSupplier supplier to get an executor to perform blocking I/O operations
     */
    @Inject
    public BlockNodeConnectionManager(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockBufferService blockBufferService,
            @NonNull final BlockStreamMetrics blockStreamMetrics,
            @NonNull @Named("bn-blockingio-exec") final Supplier<ExecutorService> blockingIoExecutorSupplier) {
        this.configProvider = requireNonNull(configProvider, "configProvider must not be null");
        this.blockBufferService = requireNonNull(blockBufferService, "blockBufferService must not be null");
        this.blockStreamMetrics = requireNonNull(blockStreamMetrics, "blockStreamMetrics must not be null");
        this.blockingIoExecutorSupplier =
                requireNonNull(blockingIoExecutorSupplier, "Blocking I/O executor supplier is required");
        this.nodeStats = new ConcurrentHashMap<>();
        this.blockNodeConfigDirectory = getAbsolutePath(blockNodeConnectionFileDir());
        this.clientFactory = new BlockNodeClientFactory();

        blockingIoExecutor = this.blockingIoExecutorSupplier.get();
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
    private List<BlockNodeConfiguration> extractBlockNodesConfigurations(@NonNull final String blockNodeConfigPath) {
        final Path configPath = Paths.get(blockNodeConfigPath, BLOCK_NODES_FILE_NAME);
        final BlockNodeConnectionInfo connectionInfo;
        final List<BlockNodeConfiguration> nodes = new ArrayList<>();

        try {
            if (!Files.exists(configPath)) {
                logger.info("Block node configuration file does not exist: {}", configPath);
                return nodes;
            }

            final byte[] jsonConfig = Files.readAllBytes(configPath);
            connectionInfo = BlockNodeConnectionInfo.JSON.parse(Bytes.wrap(jsonConfig));
        } catch (final IOException | ParseException e) {
            logger.warn(
                    "Failed to read or parse block node configuration from {}. Continuing without block node connections.",
                    configPath,
                    e);
            return nodes;
        }

        for (final BlockNodeConfig nodeConfig : connectionInfo.nodes()) {
            try {
                final BlockNodeConfiguration cfg = BlockNodeConfiguration.from(nodeConfig);
                nodes.add(cfg);
            } catch (final RuntimeException e) {
                logger.warn("Failed to parse block node configuration; skipping block node (config={})", nodeConfig, e);
            }
        }

        return nodes;
    }

    /**
     * Checks if there is only one block node configured.
     * @return whether there is only one block node configured
     */
    public boolean isOnlyOneBlockNodeConfigured() {
        final int size;
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
            @NonNull final BlockNodeStreamingConnection connection,
            @Nullable final Duration delay,
            @Nullable final Long blockNumber,
            final boolean selectNewBlockNode) {
        if (!isStreamingEnabled()) {
            return;
        }
        requireNonNull(connection, "connection must not be null");

        logger.debug("{} Closing and rescheduling connection for reconnect attempt.", connection);

        // Handle cleanup and rescheduling
        handleConnectionCleanupAndReschedule(connection, delay, blockNumber, selectNewBlockNode);
    }

    /**
     * Common logic for handling connection cleanup and rescheduling after a connection is closed.
     * This centralizes the retry and node selection logic.
     */
    private void handleConnectionCleanupAndReschedule(
            @NonNull final BlockNodeStreamingConnection connection,
            @Nullable final Duration delay,
            @Nullable final Long blockNumber,
            final boolean selectNewBlockNode) {
        final long delayMs;
        // Get or create the retry attempt for this node
        final RetryState retryState = retryStates.computeIfAbsent(connection.configuration(), k -> new RetryState());
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

        logger.info(
                "{} Apply exponential backoff and reschedule in {} ms (attempt={}).",
                connection,
                delayMs,
                retryAttempt);

        scheduleConnectionAttempt(connection.configuration(), Duration.ofMillis(delayMs), blockNumber, false);

        if (!isOnlyOneBlockNodeConfigured() && selectNewBlockNode) {
            // Immediately try to find and connect to the next available node
            selectNewBlockNodeForStreaming(false);
        }
    }

    private void scheduleConnectionAttempt(
            @NonNull final BlockNodeConfiguration blockNodeConfig,
            @NonNull final Duration initialDelay,
            @Nullable final Long initialBlockToStream,
            final boolean force) {
        if (!isStreamingEnabled()) {
            return;
        }
        requireNonNull(blockNodeConfig);
        requireNonNull(initialDelay);

        final long delayMillis = Math.max(0, initialDelay.toMillis());
        final BlockNodeStreamingConnection newConnection = createConnection(blockNodeConfig, initialBlockToStream);

        logger.debug("{} Scheduling reconnection for node in {} ms (force={}).", newConnection, delayMillis, force);

        // Schedule the first attempt using the connectionExecutor
        try {
            sharedExecutorService.schedule(
                    new BlockNodeConnectionTask(newConnection, initialDelay, force),
                    delayMillis,
                    TimeUnit.MILLISECONDS);
            logger.debug("{} Successfully scheduled reconnection task.", newConnection);
        } catch (final Exception e) {
            logger.warn("{} Failed to schedule connection task for block node.", newConnection, e);
            newConnection.closeAtBlockBoundary();
        }
    }

    /**
     * Gracefully shuts down the connection manager, closing the active connection.
     */
    public void shutdown() {
        if (!isConnectionManagerActive.compareAndSet(true, false)) {
            logger.info("Connection Manager already shutdown.");
            return;
        }
        logger.info("Shutting down block node connection manager.");

        if (blockingIoExecutor != null) {
            blockingIoExecutor.shutdownNow();
            blockingIoExecutor = null;
        }

        stopConfigWatcher();
        blockBufferService.shutdown();
        shutdownScheduledExecutorService();
        closeAllConnections();
        clearManagerMetadata();

        logger.info("Block node connection manager shutdown complete");
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
        logger.info("Stopping block node connections");
        // Close all connections
        final Iterator<Map.Entry<BlockNodeConfiguration, BlockNodeStreamingConnection>> iterator =
                connections.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<BlockNodeConfiguration, BlockNodeStreamingConnection> entry = iterator.next();
            final BlockNodeStreamingConnection connection = entry.getValue();
            try {
                // This method is invoked during a shutdown of the connection manager, in which case we don't want
                // to gracefully close connections at block boundaries, so just call close immediately.
                connection.close();
            } catch (final RuntimeException e) {
                logger.debug(
                        "{} Error while closing connection during connection manager shutdown. Ignoring.",
                        connection,
                        e);
            }
            iterator.remove();
        }
    }

    /**
     * Starts the connection manager. This will schedule a connection attempt to one of the block nodes. This does not
     * block.
     */
    public void start() {
        if (!isStreamingEnabled()) {
            logger.warn("Cannot start the connection manager, streaming is not enabled.");
            return;
        }
        if (!isConnectionManagerActive.compareAndSet(false, true)) {
            logger.info("Connection Manager already started.");
            return;
        }
        logger.info("Starting connection manager.");

        if (blockingIoExecutor == null) {
            /*
            Why the null check? We initialize the blocking I/O executor in the constructor by calling the supplier,
            but an instance of the connection manager can be shutdown and technically can be restarted. During the
            shutdown process, the executor is also shutdown (and set to null) so if the manager was started again we
            need to get another instance from the blocking I/O executor from the supplier.
             */
            blockingIoExecutor = blockingIoExecutorSupplier.get();
        }

        // Start the block buffer service
        blockBufferService.start();

        // Start a watcher to monitor changes to the block-nodes.json file for dynamic updates
        startConfigWatcher();

        refreshAvailableBlockNodes();
    }

    private void createScheduledExecutorService() {
        logger.debug("Creating scheduled executor service for the Block Node connection manager.");
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
            logger.debug("Cannot select block node, streaming is not enabled.");
            return false;
        }

        logger.debug("Selecting highest priority available block node for connection attempt.");

        final BlockNodeConfiguration selectedNode = getNextPriorityBlockNode();

        if (selectedNode == null) {
            logger.info("No available block nodes found for streaming.");
            return false;
        }

        logger.debug(
                "Selected block node {}:{} for connection attempt",
                selectedNode.address(),
                selectedNode.streamingPort());

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
    private @Nullable BlockNodeConfiguration getNextPriorityBlockNode() {
        logger.debug("Searching for new block node connection based on node priorities.");

        final List<BlockNodeConfiguration> snapshot;
        synchronized (availableBlockNodes) {
            snapshot = new ArrayList<>(availableBlockNodes);
        }

        final SortedMap<Integer, List<BlockNodeConfiguration>> priorityGroups = snapshot.stream()
                .collect(Collectors.groupingBy(BlockNodeConfiguration::priority, TreeMap::new, toList()));

        BlockNodeConfiguration selectedNode = null;

        for (final Map.Entry<Integer, List<BlockNodeConfiguration>> entry : priorityGroups.entrySet()) {
            final int priority = entry.getKey();
            final List<BlockNodeConfiguration> nodesInGroup = entry.getValue();
            try {
                selectedNode = findAvailableNode(nodesInGroup);
            } catch (final Exception e) {
                logger.warn("Error encountered while trying to find available node in priority group {}", priority, e);
            }

            if (selectedNode == null) {
                logger.debug("No available node found in priority group {}.", priority);
            } else {
                logger.debug("Found available node in priority group {}.", priority);
                return selectedNode;
            }
        }

        return selectedNode;
    }

    /**
     * Task that creates a service connection to a block node and retrieves the status of the block node.
     */
    class RetrieveBlockNodeStatusTask implements Callable<BlockNodeStatus> {

        private final BlockNodeServiceConnection svcConnection;

        RetrieveBlockNodeStatusTask(@NonNull final BlockNodeConfiguration nodeConfig) {
            requireNonNull(nodeConfig, "Node configuration is required");
            svcConnection =
                    new BlockNodeServiceConnection(configProvider, nodeConfig, blockingIoExecutor, clientFactory);
        }

        @Override
        public BlockNodeStatus call() {
            svcConnection.initialize();

            try {
                return svcConnection.getBlockNodeStatus();
            } finally {
                svcConnection.close();
            }
        }
    }

    /**
     * Given a list of available nodes, find a node that can be used for creating a new connection.
     * This ensures we always create fresh BlockNodeConnection instances for new pipelines.
     *
     * @param nodes list of possible nodes to connect to
     * @return a node that is a candidate to connect to, or null if no candidate was found
     */
    private @Nullable BlockNodeConfiguration findAvailableNode(@NonNull final List<BlockNodeConfiguration> nodes) {
        requireNonNull(nodes, "nodes must not be null");
        // Only allow the selection of nodes which are not currently in the connections map
        final List<BlockNodeConfiguration> candidateNodes = nodes.stream()
                .filter(nodeConfig -> !connections.containsKey(nodeConfig))
                .toList();

        if (candidateNodes.isEmpty()) {
            return null;
        }

        final Duration timeout = configProvider
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .blockNodeStatusTimeout();

        final List<RetrieveBlockNodeStatusTask> tasks = new ArrayList<>();
        for (final BlockNodeConfiguration nodeCfg : candidateNodes) {
            tasks.add(new RetrieveBlockNodeStatusTask(nodeCfg));
        }

        final List<Future<BlockNodeStatus>> futures = new ArrayList<>();
        try {
            futures.addAll(blockingIoExecutor.invokeAll(tasks, timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (final InterruptedException e) {
            logger.warn("Interrupted while waiting for one or more block node status retrieval tasks; ignoring group");
            Thread.currentThread().interrupt();
            return null;
        } catch (final Exception e) {
            logger.warn(
                    "Error encountered while waiting for one or more block node retrieval tasks to complete; ignoring group",
                    e);
            return null;
        }

        if (candidateNodes.size() != futures.size()) {
            // this should never happen, but we will be defensive and check anyway
            logger.warn(
                    "Number of candidates ({}) does not match the number of tasks submitted ({}); ignoring group",
                    candidateNodes.size(),
                    futures.size());
            return null;
        }

        // collect the results and filter out nodes that either are unavailable or nodes that require a block we don't
        // have available in the buffer
        final long earliestAvailableBlock = blockBufferService.getEarliestAvailableBlockNumber();
        final long latestAvailableBlock = blockBufferService.getLastBlockNumberProduced();
        final List<BlockNodeConfiguration> nodesToSelectFrom = new ArrayList<>();

        for (int i = 0; i < candidateNodes.size(); ++i) {
            final BlockNodeConfiguration nodeConfig = candidateNodes.get(i);
            final Future<BlockNodeStatus> future = futures.get(i);
            final BlockNodeStatus status =
                    switch (future.state()) {
                        case SUCCESS -> {
                            final BlockNodeStatus bns = future.resultNow();
                            if (bns == null) {
                                logger.warn(
                                        "[{}:{}] Retrieving block node status was successful, but null returned",
                                        nodeConfig.address(),
                                        nodeConfig.servicePort());
                                // we don't have any information so mark it unreachable... hopefully this never happens
                                yield BlockNodeStatus.notReachable();
                            } else {
                                logger.debug(
                                        "[{}:{}] Successfully retrieved block node status",
                                        nodeConfig.address(),
                                        nodeConfig.servicePort());
                                yield bns;
                            }
                        }
                        case FAILED -> {
                            logger.warn(
                                    "[{}:{}] Failed to retrieve block node status",
                                    nodeConfig.address(),
                                    nodeConfig.servicePort(),
                                    future.exceptionNow());
                            yield BlockNodeStatus.notReachable();
                        }
                        case CANCELLED, RUNNING -> {
                            logger.warn(
                                    "[{}:{}] Timed out waiting for block node status",
                                    nodeConfig.address(),
                                    nodeConfig.servicePort());
                            future.cancel(true);
                            yield BlockNodeStatus.notReachable();
                        }
                        default -> {
                            logger.warn(
                                    "[{}:{}] Unknown outcome while waiting for block node status",
                                    nodeConfig.address(),
                                    nodeConfig.servicePort());
                            yield BlockNodeStatus.notReachable();
                        }
                    };

            if (!status.wasReachable()) {
                logger.info(
                        "[{}:{}] Block node is not a candidate for streaming (reason: unreachable/timeout)",
                        nodeConfig.address(),
                        nodeConfig.servicePort());
                continue;
            }

            /*
            There is a scenario in which this consensus node may not have any blocks loaded. For example, this node may
            be initializing for the first time or the node may have restarted but there aren't any buffered blocks that
            were persisted. In either case, upon startup the node will not be aware of any blocks and thus the last
            produced block will be marked as -1. In this case, we will permit connecting to any block node, as long as
            it is reachable. Once this node joins the network and is able to start producing blocks, those new blocks
            will be streamed to the block node. If it turns out the block node is behind, or ahead, of the consensus
            node, then existing reconnect operations will engage to sort things out.
             */

            if (latestAvailableBlock != -1) {
                final long wantedBlock = status.latestBlockAvailable() == -1 ? -1 : status.latestBlockAvailable() + 1;

                if (wantedBlock != -1 && (wantedBlock < earliestAvailableBlock || wantedBlock > latestAvailableBlock)) {
                    logger.info(
                            "[{}:{}] Block node is not a candidate for streaming (reason: block out of range (wantedBlock: {}, blocksAvailable: {}-{}))",
                            nodeConfig.address(),
                            nodeConfig.servicePort(),
                            wantedBlock,
                            earliestAvailableBlock,
                            latestAvailableBlock);
                    continue;
                }
            }

            logger.info(
                    "[{}:{}] Block node is available for streaming", nodeConfig.address(), nodeConfig.servicePort());
            nodesToSelectFrom.add(nodeConfig);
        }

        if (nodesToSelectFrom.isEmpty()) {
            return null;
        }

        if (nodesToSelectFrom.size() == 1) {
            return nodesToSelectFrom.getFirst();
        }

        // If there are multiple good nodes, shuffle them and pick the first one.
        // This will add some randomness to the selection process.
        Collections.shuffle(nodesToSelectFrom);
        return nodesToSelectFrom.getFirst();
    }

    /**
     * Creates a BlockNodeConnection instance and immediately schedules the *first*
     * connection attempt using the retry mechanism (with zero initial delay).
     * Always creates a new instance to ensure proper Pipeline lifecycle management.
     *
     * @param nodeConfig the configuration of the node to connect to.
     */
    @NonNull
    private BlockNodeStreamingConnection createConnection(
            @NonNull final BlockNodeConfiguration nodeConfig, @Nullable final Long initialBlockToStream) {
        requireNonNull(nodeConfig);

        final BlockNodeStreamingConnection connection = new BlockNodeStreamingConnection(
                configProvider,
                nodeConfig,
                this,
                blockBufferService,
                blockStreamMetrics,
                sharedExecutorService,
                blockingIoExecutorSupplier.get(),
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
            logger.debug("Configuration watcher already running.");
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
                                        if (logger.isInfoEnabled()) {
                                            logger.info("Detected {} event for {}.", kind.name(), changed);
                                        }
                                        try {
                                            refreshAvailableBlockNodes();
                                        } catch (final Exception e) {
                                            logger.info(
                                                    "Exception in BlockNodesConfigWatcher config file change handler.",
                                                    e);
                                        }
                                    }
                                }
                            } catch (final InterruptedException | ClosedWatchServiceException e) {
                                break;
                            } catch (final Exception e) {
                                logger.info("Exception in config watcher loop.", e);
                                if (Thread.currentThread().isInterrupted()) {
                                    logger.debug("Config watcher thread interrupted, exiting.");
                                    return;
                                }
                            } finally {
                                // Always reset the key to continue watching for events, even if an exception occurred
                                if (key != null && !key.reset()) {
                                    logger.info("WatchKey could not be reset. Exiting config watcher loop.");
                                    break;
                                }
                            }
                        }
                    });
            configWatcherThreadRef.set(watcherThread);
            logger.info("Started block-nodes.json configuration watcher thread.");
        } catch (final IOException e) {
            logger.info(
                    "Failed to start block-nodes.json configuration watcher ({}). Dynamic updates disabled.",
                    e.getMessage());
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
        final List<BlockNodeConfiguration> newConfigs = extractBlockNodesConfigurations(configDir);

        // Compare new configs with existing ones to determine if a restart is needed
        synchronized (availableBlockNodes) {
            if (newConfigs.equals(availableBlockNodes)) {
                logger.info("Block node configuration unchanged. No action taken.");
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
            final StringBuilder sb = new StringBuilder("Reloaded block node configurations: \n");
            final Iterator<BlockNodeConfiguration> configIt = newConfigs.iterator();
            while (configIt.hasNext()) {
                sb.append("  ").append(configIt.next());
                if (configIt.hasNext()) {
                    sb.append("\n");
                }
            }

            logger.info("{}", sb);
            createScheduledExecutorService();
            selectNewBlockNodeForStreaming(false);
        } else {
            logger.info("No valid block node configurations available after file change. Connections remain stopped.");
        }
    }

    /**
     * Runnable task to handle the connection attempt logic.
     * Schedules itself for subsequent retries upon failure using the connectionExecutor.
     * Handles setting active connection and signaling on success.
     */
    class BlockNodeConnectionTask implements Runnable {
        private final BlockNodeStreamingConnection connection;
        private Duration currentBackoffDelayMs;
        private final boolean force;

        BlockNodeConnectionTask(
                @NonNull final BlockNodeStreamingConnection connection,
                @NonNull final Duration initialDelay,
                final boolean force) {
            this.connection = requireNonNull(connection);
            // Ensure the initial delay is non-negative for backoff calculation
            this.currentBackoffDelayMs = initialDelay.isNegative() ? Duration.ZERO : initialDelay;
            this.force = force;
        }

        /**
         * Manages the state transitions of gRPC streaming connections to Block Nodes.
         * Connection state transitions are synchronized to ensure thread-safe updates when
         * promoting connections from PENDING to ACTIVE state or handling failures.
         */
        @Override
        public void run() {
            if (!isStreamingEnabled()) {
                logger.debug("{} Cannot run connection task, streaming is not enabled.", connection);
                return;
            }

            if (!isConnectionManagerActive.get()) {
                logger.debug("{} Cannot run connection task, connection manager has shutdown.", connection);
                return;
            }

            try {
                logger.debug("{} Running connection task.", connection);
                final BlockNodeStreamingConnection activeConnection = activeConnectionRef.get();

                if (activeConnection != null) {
                    if (activeConnection.equals(connection)) {
                        // not sure how the active connection is in a connectivity task, ignoring
                        logger.debug("{} The current connection is the active connection, ignoring task.", connection);
                        return;
                    } else if (force) {
                        final BlockNodeConfiguration newConnConfig = connection.configuration();
                        final BlockNodeConfiguration oldConnConfig = activeConnection.configuration();
                        if (logger.isDebugEnabled()) {
                            logger.debug(
                                    "{} Promoting forced connection with priority={} over active ({}:{} priority={}).",
                                    connection,
                                    newConnConfig.priority(),
                                    oldConnConfig.address(),
                                    oldConnConfig.streamingPort(),
                                    oldConnConfig.priority());
                        }
                    } else if (activeConnection.configuration().priority()
                            <= connection.configuration().priority()) {
                        // this new connection has a lower (or equal) priority than the existing active connection
                        // this connection task should thus be cancelled/ignored
                        logger.info(
                                "{} Active connection has equal/higher priority. Ignoring candidate. Active: {}.",
                                connection,
                                activeConnection);
                        // This connection was never initialized so we are safe to call close immediately
                        connection.close(false);
                        return;
                    }
                }

                /*
                If we have got to this point, it means there is no active connection, or it means there is an active
                connection, but the active connection has a lower priority than the connection in this task. In either
                case, we want to elevate this connection to be the new active connection.
                 */
                connection.initialize();

                if (activeConnectionRef.compareAndSet(activeConnection, connection)) {
                    // we were able to elevate this connection to the new active one
                    connection.updateConnectionState(ConnectionState.ACTIVE);
                    recordActiveConnectionIp(connection.configuration());
                } else {
                    // Another connection task has preempted this task, reschedule and try again
                    logger.info("{} Current connection task was preempted, rescheduling.", connection);
                    reschedule();
                }

                if (activeConnection != null) {
                    // close the old active connection
                    try {
                        logger.info("{} Closing current active connection {}.", connection, activeConnection);
                        activeConnection.closeAtBlockBoundary();

                        // For a forced switch, reschedule the previously active connection to try again later
                        if (force) {
                            try {
                                final Duration delay = getForcedSwitchRescheduleDelay();
                                scheduleConnectionAttempt(activeConnection.configuration(), delay, null, false);
                                logger.info(
                                        "Scheduled previously active connection {} in {} ms due to forced switch.",
                                        activeConnection,
                                        delay.toMillis());
                            } catch (final Exception e) {
                                logger.warn(
                                        "Failed to schedule reschedule for previous active connection after forced switch.",
                                        e);
                                connections.remove(activeConnection.configuration());
                            }
                        }
                    } catch (final RuntimeException e) {
                        logger.info(
                                "Failed to shutdown current active connection {} (shutdown reason: another connection was elevated to active).",
                                activeConnection,
                                e);
                    }
                }
            } catch (final Exception e) {
                logger.warn("{} Failed to establish connection to block node. Will schedule a retry.", connection, e);
                blockStreamMetrics.recordConnectionCreateFailure();
                reschedule();
                selectNewBlockNodeForStreaming(false);
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
                    if (!availableBlockNodes.contains(connection.configuration())) {
                        logger.debug("{} Node no longer available, skipping reschedule.", connection);
                        connections.remove(connection.configuration());
                        return;
                    }
                }
                sharedExecutorService.schedule(this, jitteredDelayMs, TimeUnit.MILLISECONDS);
                logger.info("{} Rescheduled connection attempt (delayMillis={}).", connection, jitteredDelayMs);
            } catch (final Exception e) {
                logger.warn("{} Failed to reschedule connection attempt. Removing from retry map.", connection, e);
                connection.closeAtBlockBoundary();
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
            @NonNull final BlockNodeConfiguration blockNodeConfig, @NonNull final Instant timestamp) {
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

    private Duration getForcedSwitchRescheduleDelay() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .forcedSwitchRescheduleDelay();
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
    public int getEndOfStreamCount(@NonNull final BlockNodeConfiguration blockNodeConfig) {
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

    private void recordActiveConnectionIp(final BlockNodeConfiguration nodeConfig) {
        long ipAsInteger;

        // Attempt to resolve the address of the block node
        try {
            final URL blockNodeUrl = URI.create("http://" + nodeConfig.address() + ":" + nodeConfig.streamingPort())
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

            if (logger.isInfoEnabled()) {
                logger.info(
                        "Active block node connection updated to: {}:{} (resolvedIp: {}, resolvedIpAsInt={})",
                        nodeConfig.address(),
                        nodeConfig.streamingPort(),
                        blockAddress.getHostAddress(),
                        ipAsInteger);
            }
        } catch (final IOException e) {
            logger.debug(
                    "Failed to resolve block node host ({}:{})", nodeConfig.address(), nodeConfig.streamingPort(), e);
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
            @NonNull final BlockNodeConfiguration blockNodeConfig,
            final long blockNumber,
            @NonNull final Instant timestamp) {
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
            @NonNull final BlockNodeConfiguration blockNodeConfig,
            final long blockNumber,
            @NonNull final Instant timestamp) {
        if (!isStreamingEnabled()) {
            return new BlockNodeStats.HighLatencyResult(0L, 0, false, false);
        }
        requireNonNull(blockNodeConfig, "blockNodeConfig must not be null");

        final BlockNodeStats stats = nodeStats.computeIfAbsent(blockNodeConfig, k -> new BlockNodeStats());
        final BlockNodeStats.HighLatencyResult result = stats.recordAcknowledgementAndEvaluate(
                blockNumber, timestamp, getHighLatencyThreshold(), getHighLatencyEventsBeforeSwitching());
        final long latencyMs = result.latencyMs();

        // Update metrics
        if (result.isHighLatency()) {
            if (logger.isDebugEnabled()) {
                logger.debug(
                        "[{}] A high latency event ({}ms) has occurred. A total of {} consecutive events",
                        blockNodeConfig,
                        latencyMs,
                        result.consecutiveHighLatencyEvents());
            }
            blockStreamMetrics.recordHighLatencyEvent();
        }

        return result;
    }

    /**
     * Notifies the connection manager that a connection has been closed.
     * This allows the manager to update its internal state accordingly.
     * @param connection the connection that has been closed
     */
    public void notifyConnectionClosed(@NonNull final BlockNodeStreamingConnection connection) {
        // Remove from active connection if it is the current active
        activeConnectionRef.compareAndSet(connection, null);

        // Remove from connections map
        connections.remove(connection.configuration());
    }
}
