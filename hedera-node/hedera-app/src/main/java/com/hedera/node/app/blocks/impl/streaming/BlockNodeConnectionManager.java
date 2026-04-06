// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeEndpoint;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
     * Manager that maintains the block stream on this consensus node.
     */
    private final BlockBufferService blockBufferService;
    /**
     * Metrics API for block stream-specific metrics.
     */
    private final BlockStreamMetrics blockStreamMetrics;
    /**
     * Mechanism to retrieve configuration properties related to block-node communication.
     */
    private final ConfigProvider configProvider;
    /**
     * Flag that indicates if this connection manager is active or not. In this case, being active means it is actively
     * processing blocks and attempting to send them to a block node.
     */
    private final AtomicBoolean isConnectionManagerActive = new AtomicBoolean(false);
    /**
     * Service used to retrieve block node configurations.
     */
    private final BlockNodeConfigService blockNodeConfigService;
    /**
     * Reference to the currently active connection. If this reference is null, then there is no active connection.
     */
    private final AtomicReference<BlockNodeStreamingConnection> activeConnectionRef = new AtomicReference<>();
    /**
     * Factory for creating new block node clients.
     */
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
     * The available block nodes, based on the latest active configuration, by node address.
     */
    private final ConcurrentMap<BlockNodeEndpoint, BlockNode> nodes = new ConcurrentHashMap<>();
    /**
     * Counter for tracking the total number of active block node streaming connections across all block nodes.
     */
    private final AtomicInteger globalActiveStreamingConnectionCount = new AtomicInteger();
    /**
     * The most recent configuration used. This configuration is used to compare against newer configs to know if a
     * configuration change has occurred.
     */
    private final AtomicReference<VersionedBlockNodeConfigurationSet> activeConfigRef = new AtomicReference<>();
    /**
     * The timestamp which represents the earliest time a connection change can be performed - unless forced.
     */
    private final AtomicReference<Instant> globalCoolDownTimestampRef = new AtomicReference<>();
    /**
     * Most recent block buffer status.
     */
    private final AtomicReference<BlockBufferStatus> bufferStatusRef =
            new AtomicReference<>(new BlockBufferStatus(Instant.MIN, 0.0D, false));
    /**
     * Reference to the connection monitor thread.
     */
    private final AtomicReference<Thread> connectionMonitorThreadRef = new AtomicReference<>();
    /**
     * Numeric ID of this consensus node used in connection correlation IDs.
     */
    private final long selfNodeId;
    /**
     * A record that holds a candidate node configuration along with the block number it wants to stream.
     *
     * @param node      the block node
     * @param wantedBlock the block number the block node wants to receive next
     */
    record NodeCandidate(BlockNode node, long wantedBlock) {}

    /**
     * Outcome of evaluating one priority group.
     *
     * @param inRangeCandidates       candidates this CN can stream to immediately
     * @param lowestAheadCandidates   candidates tied for lowest wanted block (when all candidates are ahead)
     * @param lowestAheadWantedBlock  the lowest wanted block among ahead candidates
     */
    record GroupSelectionOutcome(
            List<NodeCandidate> inRangeCandidates,
            List<NodeCandidate> lowestAheadCandidates,
            long lowestAheadWantedBlock) {}

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
            @NonNull final NetworkInfo networkInfo,
            @NonNull @Named("bn-blockingio-exec") final Supplier<ExecutorService> blockingIoExecutorSupplier,
            @NonNull final BlockNodeConfigService blockNodeConfigService) {
        this.configProvider = requireNonNull(configProvider, "configProvider must not be null");
        this.blockBufferService = requireNonNull(blockBufferService, "blockBufferService must not be null");
        this.blockStreamMetrics = requireNonNull(blockStreamMetrics, "blockStreamMetrics must not be null");
        this.selfNodeId = requireNonNull(networkInfo, "networkInfo must not be null")
                .selfNodeInfo()
                .nodeId();
        this.blockingIoExecutorSupplier =
                requireNonNull(blockingIoExecutorSupplier, "Blocking I/O executor supplier is required");
        this.clientFactory = new BlockNodeClientFactory();
        this.blockNodeConfigService = requireNonNull(blockNodeConfigService, "Block node config service is required");

        blockingIoExecutor = blockingIoExecutorSupplier.get();
    }

    /**
     * @return the current {@link BlockNodeConnectionConfig} instance
     */
    private @NonNull BlockNodeConnectionConfig bncConfig() {
        return configProvider.getConfiguration().getConfigData(BlockNodeConnectionConfig.class);
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
     * Gracefully shuts down the connection manager, closing the active connection.
     */
    public void shutdown() {
        if (!isConnectionManagerActive.compareAndSet(true, false)) {
            logger.info("Attempted to shutdown block node connection manager, but it is already shutdown");
            return;
        }
        logger.info("Shutting down block node connection manager...");

        if (blockingIoExecutor != null) {
            blockingIoExecutor.shutdownNow();
            blockingIoExecutor = null;
        }

        blockNodeConfigService.shutdown();
        blockBufferService.shutdown();

        // clear connection monitor thread reference
        connectionMonitorThreadRef.set(null);

        for (final BlockNode node : nodes.values()) {
            node.onTerminate(CloseReason.SHUTDOWN);
        }

        activeConnectionRef.set(null);

        logger.info("Block node connection manager shutdown");
    }

    /**
     * Starts the connection manager. This will schedule a connection attempt to one of the block nodes. This does not
     * block.
     */
    public void start() {
        if (!isStreamingEnabled()) {
            logger.warn("Streaming is not enabled; block node connection manager will not be started");
            return;
        }
        if (!isConnectionManagerActive.compareAndSet(false, true)) {
            logger.info("Attempted to start block node connection manager, but it is already started");
            return;
        }
        logger.info("Starting block node connection manager...");

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
        blockNodeConfigService.start();

        // Start the background monitor thread
        final Thread connectionMonitorThread = new Thread(new ConnectionMonitorTask(), "bn-conn-monitor");
        if (connectionMonitorThreadRef.compareAndSet(null, connectionMonitorThread)) {
            connectionMonitorThread.setDaemon(true);
            connectionMonitorThread.start();
        }

        logger.info("Block node connection manager started");
    }

    /**
     * Selects the next available block node based on priority.
     * It will skip over any nodes that are already in retry or have a lower priority than the current active connection.
     *
     * @return the next available block node
     */
    private @Nullable BlockNode getNextPriorityBlockNode(@Nullable final List<BlockNode> availableBlockNodes) {
        requireNonNull(availableBlockNodes, "Available block nodes list is required");
        logger.debug("Searching for new block node connection based on node priorities.");

        final SortedMap<Integer, List<BlockNode>> priorityGroups = availableBlockNodes.stream()
                .collect(Collectors.groupingBy(node -> node.configuration().priority(), TreeMap::new, toList()));

        final List<NodeCandidate> globalLowestAheadCandidates = new ArrayList<>();
        long globalLowestWantedBlock = Long.MAX_VALUE;

        for (final Map.Entry<Integer, List<BlockNode>> entry : priorityGroups.entrySet()) {
            final int priority = entry.getKey();
            final List<BlockNode> nodesInGroup = entry.getValue();
            final GroupSelectionOutcome outcome;
            try {
                outcome = findAvailableNode(nodesInGroup);
            } catch (final Exception e) {
                logger.warn("Error encountered while trying to find available node in priority group {}", priority, e);
                continue;
            }

            if (outcome == null) {
                logger.debug("No available node found in priority group {}.", priority);
                continue;
            }

            if (!outcome.inRangeCandidates().isEmpty()) {
                logger.debug("Found in-range available node in priority group {}.", priority);
                return selectRandomCandidate(outcome.inRangeCandidates());
            }

            if (outcome.lowestAheadWantedBlock() < globalLowestWantedBlock) {
                globalLowestWantedBlock = outcome.lowestAheadWantedBlock();
                globalLowestAheadCandidates.clear();
                globalLowestAheadCandidates.addAll(outcome.lowestAheadCandidates());
            } else if (outcome.lowestAheadWantedBlock() == globalLowestWantedBlock) {
                globalLowestAheadCandidates.addAll(outcome.lowestAheadCandidates());
            }
        }

        if (globalLowestAheadCandidates.isEmpty()) {
            return null;
        }

        logger.debug(
                "All groups only had ahead candidates. Selecting from global lowest wantedBlock={}",
                globalLowestWantedBlock);
        return selectRandomCandidate(globalLowestAheadCandidates);
    }

    /**
     * Task that creates a service connection to a block node and retrieves the status of the block node.
     */
    static class RetrieveBlockNodeStatusTask implements Callable<BlockNodeStatus> {

        private final BlockNodeServiceConnection svcConnection;

        RetrieveBlockNodeStatusTask(@NonNull final BlockNodeServiceConnection svcConnection) {
            this.svcConnection = requireNonNull(svcConnection, "Service connection is required");
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
     * @return outcome for this priority group, or null if no candidates were eligible
     */
    private @Nullable GroupSelectionOutcome findAvailableNode(@NonNull final List<BlockNode> nodes) {
        requireNonNull(nodes, "nodes must not be null");

        if (nodes.isEmpty()) {
            return null;
        }

        final Duration timeout = bncConfig().blockNodeStatusTimeout();

        final List<RetrieveBlockNodeStatusTask> tasks = new ArrayList<>();
        for (final BlockNode node : nodes) {
            final BlockNodeServiceConnection svcConnection = new BlockNodeServiceConnection(
                    configProvider, node.configuration(), blockingIoExecutor, clientFactory, selfNodeId);
            tasks.add(new RetrieveBlockNodeStatusTask(svcConnection));
        }

        final List<Future<BlockNodeStatus>> futures;
        try {
            futures = new ArrayList<>(blockingIoExecutor.invokeAll(tasks, timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (final InterruptedException _) {
            logger.warn("Interrupted while waiting for one or more block node status retrieval tasks; ignoring group");
            Thread.currentThread().interrupt();
            return null;
        } catch (final Exception e) {
            logger.warn(
                    "Error encountered while waiting for one or more block node retrieval tasks to complete; ignoring group",
                    e);
            return null;
        }

        if (nodes.size() != futures.size()) {
            // this should never happen, but we will be defensive and check anyway
            logger.warn(
                    "Number of candidates ({}) does not match the number of tasks submitted ({}); ignoring group",
                    nodes.size(),
                    futures.size());
            return null;
        }

        // collect the results and filter out nodes that either are unavailable or nodes that require a block we don't
        // have available in the buffer
        final long earliestAvailableBlock = blockBufferService.getEarliestAvailableBlockNumber();
        final long latestAvailableBlock = blockBufferService.getLastBlockNumberProduced();
        final List<NodeCandidate> eligibleCandidates = new ArrayList<>();

        for (int i = 0; i < nodes.size(); ++i) {
            final BlockNode node = nodes.get(i);
            final BlockNodeConfiguration nodeConfig = node.configuration();
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

            final long wantedBlock = status.latestBlockAvailable() == -1 ? -1 : status.latestBlockAvailable() + 1;
            if (latestAvailableBlock != -1) {
                if (wantedBlock != -1 && wantedBlock < earliestAvailableBlock) {
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
                    "[{}:{}] Block node is available for streaming (wantedBlock: {})",
                    nodeConfig.address(),
                    nodeConfig.servicePort(),
                    wantedBlock);
            eligibleCandidates.add(new NodeCandidate(node, wantedBlock));
        }

        if (eligibleCandidates.isEmpty()) {
            return null;
        }

        if (latestAvailableBlock == -1) {
            // Startup case: treat all reachable candidates as immediately streamable.
            return new GroupSelectionOutcome(eligibleCandidates, List.of(), Long.MAX_VALUE);
        }

        final List<NodeCandidate> inRangeCandidates = eligibleCandidates.stream()
                .filter(c -> c.wantedBlock() <= latestAvailableBlock)
                .toList();
        if (!inRangeCandidates.isEmpty()) {
            return new GroupSelectionOutcome(inRangeCandidates, List.of(), Long.MAX_VALUE);
        }

        final long lowestAheadWantedBlock = eligibleCandidates.stream()
                .mapToLong(NodeCandidate::wantedBlock)
                .min()
                .orElse(Long.MAX_VALUE);
        final List<NodeCandidate> lowestAheadCandidates = eligibleCandidates.stream()
                .filter(c -> c.wantedBlock() == lowestAheadWantedBlock)
                .toList();
        return new GroupSelectionOutcome(List.of(), lowestAheadCandidates, lowestAheadWantedBlock);
    }

    /**
     * Selects a random node from the specified list of candidates.
     *
     * @param candidates the list of candidates to choose from
     * @return the random block node
     */
    private @NonNull BlockNode selectRandomCandidate(@NonNull final List<NodeCandidate> candidates) {
        requireNonNull(candidates, "candidates must not be null");
        if (candidates.size() == 1) {
            return candidates.getFirst().node();
        }
        final List<NodeCandidate> shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled);
        return shuffled.getFirst().node();
    }

    /**
     * Notifies the connection manager that the specified connection has been closed.
     *
     * @param connection the connection that has been closed
     */
    public void notifyConnectionClosed(@NonNull final BlockNodeStreamingConnection connection) {
        // Remove from active connection if it is the current active
        activeConnectionRef.compareAndSet(connection, null);

        final BlockNodeConfiguration config = connection.configuration();
        final BlockNode node = nodes.get(config.streamingEndpoint());

        if (node == null) {
            logger.warn(
                    "{} Connection is not associated with a known block node; ignoring close notification", connection);
        } else {
            node.onClose(connection);
        }
    }

    /**
     * Notifies the connection manager that the specified connection has been activated.
     *
     * @param connection the connection that has been activated
     */
    public void notifyConnectionActive(@NonNull final BlockNodeStreamingConnection connection) {
        final BlockNodeConfiguration config = connection.configuration();
        final BlockNode node = nodes.get(config.streamingEndpoint());

        if (node == null) {
            logger.warn(
                    "{} Connection is not associated with a known block node; ignoring open notification and closing connection",
                    connection);
            connection.close(CloseReason.INTERNAL_ERROR, true);
        } else {
            node.onActive(connection);
        }
    }

    /**
     * Task that performs the connectivity checks and any corrective actions for the lifespan of the connection manager.
     */
    class ConnectionMonitorTask implements Runnable {

        @Override
        public void run() {
            while (isConnectionManagerActive.get()) {
                try {
                    updateConnectionIfNeeded();

                    Thread.sleep(bncConfig().connectionMonitorCheckIntervalMillis());
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Connection monitor loop was interrupted; continuing", e);
                } catch (final Exception e) {
                    logger.warn("Error caught in connection monitor loop; continuing", e);
                }
            }
        }
    }

    /**
     * Checks if the current state of connectivity is healthy and if not, it will attempt to take corrective actions.
     */
    private void updateConnectionIfNeeded() {
        // record the latest number of active connections
        blockStreamMetrics.recordActiveConnectionCount(globalActiveStreamingConnectionCount.get());

        final Instant now = Instant.now();
        final BlockNodeStreamingConnection activeConnection = activeConnectionRef.get();
        CloseReason closeReason = null;

        final boolean noActiveConnection = isMissingActiveConnection(activeConnection);
        final boolean updatedConfig = isConfigUpdated();
        if (updatedConfig) {
            closeReason = CloseReason.CONFIG_UPDATE;
        }
        final boolean bufferActionStage = isBufferUnhealthy();
        if (bufferActionStage) {
            closeReason = CloseReason.BUFFER_SATURATION;
        }
        final boolean higherPriorityConnectionFound = isHigherPriorityNodeAvailable(activeConnection);
        if (higherPriorityConnectionFound) {
            closeReason = CloseReason.HIGHER_PRIORITY_FOUND;
        }
        final boolean stalledActiveConnection = isActiveConnectionStalled(now, activeConnection);
        if (stalledActiveConnection) {
            closeReason = CloseReason.CONNECTION_STALLED;
        }
        final boolean activeConnectionAutoReset = isActiveConnectionAutoReset(now, activeConnection);
        if (activeConnectionAutoReset) {
            closeReason = CloseReason.PERIODIC_RESET;
        }

        final boolean updateConnection = noActiveConnection
                || updatedConfig
                || bufferActionStage
                || higherPriorityConnectionFound
                || stalledActiveConnection
                || activeConnectionAutoReset;

        if (updateConnection) {
            if (logger.isInfoEnabled()) {
                final StringBuilder sb = new StringBuilder("Streaming connection update requested (reason:");
                if (noActiveConnection) {
                    sb.append(" missing-active-connection");
                }
                if (updatedConfig) {
                    sb.append(" config-updated");
                }
                if (bufferActionStage) {
                    sb.append(" buffer-unhealthy");
                }
                if (higherPriorityConnectionFound) {
                    sb.append(" high-priority-connection-found");
                }
                if (stalledActiveConnection) {
                    sb.append(" stalled-active-connection");
                }
                if (activeConnectionAutoReset) {
                    sb.append(" auto-reset-active-connection");
                }
                sb.append(")");

                logger.info("{}", sb);
            }

            selectNewBlockNode(noActiveConnection, closeReason);
        } else {
            logger.trace("Block node connectivity is healthy; no corrective action needed at this time");
        }
    }

    /**
     * Simple test to determine if the specified active connection exists or not.
     *
     * @param activeConnection the connection to test
     * @return true if the connection is null, else false
     */
    private boolean isMissingActiveConnection(@Nullable final BlockNodeStreamingConnection activeConnection) {
        return activeConnection == null;
    }

    /**
     * Checks if the configuration for the block nodes has been updated. Depending on the configuration change,
     * different actions will be taken:
     * <ul>
     *     <li>If all configurations are removed, then all currently tracked block nodes will be notified to terminate
     *     any active connections.</li>
     *     <li>If a new block node was added, then it will be included in the list of tracked block nodes. This should
     *     trigger a node selection process to maybe switch the active connection.</li>
     *     <li>If a block node was updated, then the change is recorded. This should trigger a node selection process
     *     to maybe switch the active connection.</li>
     *     <li>If a block node was removed, then the tracked block node is notified to terminate any active connections.
     *     This may trigger a node selection process to switch to a different active connection.</li>
     * </ul>
     *
     * If any of the above are encountered, the new configuration will become tracked by the connection manager.
     *
     * @return true if the configuration was updated, else false
     */
    private boolean isConfigUpdated() {
        final VersionedBlockNodeConfigurationSet latestConfig = blockNodeConfigService.latestConfiguration();
        final VersionedBlockNodeConfigurationSet activeConfig = activeConfigRef.get();

        if (latestConfig == null && activeConfig != null) {
            logger.info("All block node configurations removed");
            // config was removed, close all connections
            for (final BlockNode node : nodes.values()) {
                node.onTerminate(CloseReason.CONFIG_UPDATE);
            }
            activeConfigRef.set(null);
            return true;
        } else if (latestConfig != null
                && (activeConfig == null || latestConfig.versionNumber() > activeConfig.versionNumber())) {
            boolean changesDetected = false;

            // config has changed, update connections if needed
            final List<BlockNodeConfiguration> newNodeConfigs = latestConfig.configs();
            for (final BlockNodeConfiguration newNodeConfig : newNodeConfigs) {
                final BlockNode existingNode = nodes.get(newNodeConfig.streamingEndpoint());
                if (existingNode == null) {
                    // a new node was added
                    final BlockNodeEndpoint endpoint = newNodeConfig.streamingEndpoint();
                    logger.info("[{}:{}] Block node configuration was added", endpoint.host(), endpoint.port());
                    nodes.put(
                            newNodeConfig.streamingEndpoint(),
                            new BlockNode(
                                    configProvider,
                                    newNodeConfig,
                                    globalActiveStreamingConnectionCount,
                                    new BlockNodeStats()));
                    changesDetected = true;
                } else if (!existingNode.configuration().equals(newNodeConfig)) {
                    // the node has an updated configuration
                    final BlockNodeEndpoint endpoint = newNodeConfig.streamingEndpoint();
                    logger.info("[{}:{}] Block node configuration was updated", endpoint.host(), endpoint.port());
                    existingNode.onConfigUpdate(newNodeConfig);
                    changesDetected = true;
                }
            }

            final Set<BlockNodeEndpoint> newEndpoints = newNodeConfigs.stream()
                    .map(BlockNodeConfiguration::streamingEndpoint)
                    .collect(Collectors.toSet());

            for (final BlockNode node : nodes.values()) {
                final BlockNodeEndpoint endpoint = node.configuration().streamingEndpoint();
                if (!newEndpoints.contains(endpoint)) {
                    // the node was removed from the configuration
                    logger.info("[{}:{}] Block node configuration was removed", endpoint.host(), endpoint.port());
                    node.onTerminate(CloseReason.CONFIG_UPDATE);
                    changesDetected = true;
                }
            }

            if (changesDetected) {
                activeConfigRef.set(latestConfig);
            }
            return changesDetected;
        }

        return false;
    }

    /**
     * Checks if the block buffer is considered unhealthy based on whether the buffer has entered the action stage.
     *
     * @return true if the buffer is unhealthy, else false
     */
    private boolean isBufferUnhealthy() {
        final BlockBufferStatus previousBufferStatus = bufferStatusRef.get();
        final BlockBufferStatus latestBufferStatus = blockBufferService.latestBufferStatus();

        if (latestBufferStatus != null && latestBufferStatus.timestamp().isAfter(previousBufferStatus.timestamp())) {
            // a new block buffer status is available, let's check if things are trending in a good direction or not
            bufferStatusRef.set(latestBufferStatus);

            if (latestBufferStatus.isActionStage()) {
                // the latest status indicates we are above the action stage and thus should take some action
                // but, if the saturation is decreasing since the last check, don't switch connections yet and
                // hope we are able to recover
                if (latestBufferStatus.saturationPercent() >= previousBufferStatus.saturationPercent()) {
                    // saturation has stayed the same or increased, we need to attempt switching connections
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Checks if there is another block node with a higher priority than the specified active connection.
     *
     * @param activeConnection the connection to compare against
     * @return true if there is a higher priority node available, else false
     */
    private boolean isHigherPriorityNodeAvailable(@Nullable final BlockNodeStreamingConnection activeConnection) {
        if (activeConnection == null) {
            return false;
        }

        final BlockNodeConfiguration activeConnConfig = activeConnection.configuration();

        for (final Map.Entry<BlockNodeEndpoint, BlockNode> nodeEntry : nodes.entrySet()) {
            if (nodeEntry.getKey().equals(activeConnection.configuration().streamingEndpoint())) {
                continue;
            }

            final BlockNode node = nodeEntry.getValue();

            if (node.isStreamingCandidate() && node.configuration().priority() < activeConnConfig.priority()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if the specified active connection has stalled (i.e. is blocked or not progressing for an extended period
     * of time.) If so, the connection will be closed.
     *
     * @param now timestamp used compare against the last heartbeat of the connection
     * @param activeConnection the connection to check if stalled
     * @return true if the connection is stalled, else false
     */
    private boolean isActiveConnectionStalled(
            @NonNull final Instant now, @Nullable final BlockNodeStreamingConnection activeConnection) {
        if (activeConnection == null) {
            return false;
        }

        final long stalledConnectionThresholdMillis = bncConfig().connectionStallThresholdMillis();
        final long lastHeartbeatTimestamp = activeConnection.heartbeatTimestamp();

        if (lastHeartbeatTimestamp != -1) {
            final long deltaMillis = now.toEpochMilli() - lastHeartbeatTimestamp;
            if (deltaMillis >= stalledConnectionThresholdMillis) {
                logger.warn(
                        "{} Active connection is marked as being stalled (lastHeartbeat: {}, thresholdMillis: {}, deltaMillis: {}); closing connection",
                        activeConnection,
                        lastHeartbeatTimestamp,
                        stalledConnectionThresholdMillis,
                        deltaMillis);
                activeConnection.close(CloseReason.CONNECTION_STALLED, true);
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if the specified connection has reached its auto-reset threshold and should be closed and recreated. If
     * so, the connection will be closed.
     *
     * @param now timestamp used to compare the configured auto-reset time associated with the connection
     * @param activeConnection the connection to check
     * @return true if the specified connection should be reset, else false
     */
    private boolean isActiveConnectionAutoReset(
            @NonNull final Instant now, @Nullable final BlockNodeStreamingConnection activeConnection) {
        if (activeConnection == null) {
            return false;
        }

        final Instant autoResetTimestamp = activeConnection.autoResetTimestamp();
        if (now.isAfter(autoResetTimestamp)) {
            logger.info(
                    "{} Active connection has reached its auto reset time; closing connection at next block boundary",
                    activeConnection);
            activeConnection.closeAtBlockBoundary(CloseReason.PERIODIC_RESET);
            return true;
        }

        return false;
    }

    /**
     * Attempts to select a new block node to connect to, if one is available. Unless switching is forced, this method
     * will not switch the connection if we are currently in a cool down period. This cool down period is to prevent
     * too frequent switching of connections.
     *
     * @param force if true, a new block node will be selected regardless if a cool down is in place
     * @param closeReason if there is an active connection and a switch is performed, this reason will be applied to
     *                    the active connection for the reason why the switch was required
     */
    private void selectNewBlockNode(final boolean force, @Nullable final CloseReason closeReason) {
        pruneNodes();

        final Instant globalCoolDownTimestamp = globalCoolDownTimestampRef.get();

        if (globalCoolDownTimestamp != null && Instant.now().isBefore(globalCoolDownTimestamp) && !force) {
            logger.trace(
                    "Selecting a new block node is deferred due to global cool down until {}", globalCoolDownTimestamp);
            return;
        }

        if (logger.isDebugEnabled()) {
            // log the available nodes and their connection history
            final StringBuilder sb = new StringBuilder("Block Nodes:");
            if (nodes.isEmpty()) {
                sb.append(" <none>");
            } else {
                for (final BlockNode node : nodes.values()) {
                    sb.append("\n");
                    node.writeInformation(sb);
                }
            }

            logger.debug("{}", sb);
        }

        // determine which nodes are candidates to connect to
        final List<BlockNode> candidates = new ArrayList<>(nodes.size());
        for (final BlockNode node : nodes.values()) {
            if (node.isStreamingCandidate()) {
                candidates.add(node);
            }
        }

        final BlockNode selectedNode = getNextPriorityBlockNode(candidates);

        if (selectedNode == null) {
            logger.warn("No other block nodes found available for streaming");
            return;
        }

        final BlockNodeEndpoint endpoint = selectedNode.configuration().streamingEndpoint();
        logger.info("Selected new block node for streaming: {}:{}", endpoint.host(), endpoint.port());
        final BlockNodeStreamingConnection connection = new BlockNodeStreamingConnection(
                configProvider,
                selectedNode,
                this,
                blockBufferService,
                blockStreamMetrics,
                blockingIoExecutorSupplier.get(),
                null,
                clientFactory,
                selfNodeId);
        connection.initialize();
        connection.updateConnectionState(ConnectionState.ACTIVE);

        final BlockNodeStreamingConnection oldConnection = activeConnectionRef.getAndSet(connection);
        if (oldConnection != null) {
            oldConnection.closeAtBlockBoundary(closeReason);
        }

        // set the global cool down so we don't try to switch connections too frequently
        final int coolDownSeconds = bncConfig().connectCoolDownSeconds();
        globalCoolDownTimestampRef.set(Instant.now().plusSeconds(coolDownSeconds));
    }

    /**
     * Prune terminal nodes from the block node list. Only nodes that have been marked terminal and have had their
     * connection(s) closed are candidates for removal.
     */
    private void pruneNodes() {
        final Iterator<Map.Entry<BlockNodeEndpoint, BlockNode>> it =
                nodes.entrySet().iterator();
        while (it.hasNext()) {
            final BlockNode node = it.next().getValue();
            if (node.isRemovable()) {
                it.remove();
            }
        }
    }
}
