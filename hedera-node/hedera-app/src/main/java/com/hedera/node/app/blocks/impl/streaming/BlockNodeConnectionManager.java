// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

import com.hedera.node.app.blocks.impl.streaming.BlockNode.BlockNodeOutOfRange;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeEndpoint;
import com.hedera.node.app.blocks.impl.streaming.obs.BlockStreamingObs;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.config.ConfigProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
import org.hiero.block.api.PublishStreamRequest.EndStream;

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
public class BlockNodeConnectionManager extends BlockStreamingBase {

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
    private final AtomicReference<BlockNodeStreamingConnection> activeStreamingConnectionRef = new AtomicReference<>();
    /**
     * Map that tracks the current active acknowledgement observing connections.
     */
    private final ConcurrentMap<BlockNode, BlockNodeObservingConnection> activeObservingConnections =
            new ConcurrentHashMap<>();
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
    private final AtomicReference<ExecutorService> blockingIoExecutorRef = new AtomicReference<>();
    /**
     * The available block nodes, based on the latest active configuration, by node address.
     */
    private final ConcurrentMap<BlockNodeEndpoint, BlockNode> nodes = new ConcurrentHashMap<>();
    /**
     * Counter for tracking the total number of active block node streaming connections across all block nodes.
     */
    private final AtomicInteger globalActiveStreamingConnectionCount = new AtomicInteger();
    /**
     * Counter for tracking the total number of active block node observing connections across all block nodes.
     */
    private final AtomicInteger globalActiveObservingConnectionCount = new AtomicInteger();
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
     * The timestamp of the latest attempt to achieve an ideal number of observers.
     */
    private final AtomicReference<Instant> idealObserversLastAttemptTimestampRef = new AtomicReference<>(Instant.MIN);
    /**
     * Most recent block buffer saturation status.
     */
    private final AtomicReference<BlockBufferSaturationInfo> bufferSaturationRef =
            new AtomicReference<>(BlockBufferSaturationInfo.NIL);
    /**
     * Reference to the connection monitor thread.
     */
    private final AtomicReference<Thread> connectionMonitorThreadRef = new AtomicReference<>();
    /**
     * Numeric ID of this consensus node used in connection correlation IDs.
     */
    private final long selfNodeId;
    /**
     * Flag indicating whether potentially noisy logs generated during the connection monitor execution should be
     * suppressed. Because the connection monitor is triggered multiple times per second, some logs may be generated
     * very frequently and contribute to log spam. This flag helps to mitigate this.
     */
    private volatile boolean suppressNoisyMonitorLogging = false;
    /**
     * Tracks the most recent changes detected by the connection monitor. This uses bit masking to track the different
     * changes detected. This field is related to suppressing noisy monitor logging.
     */
    private volatile long latestChanges = NO_CHANGES;
    // Masks related to different types of changes that the monitor can detect
    private static final long NO_CHANGES = 0;
    private static final long MASK_NO_ACTIVE_CONNECTION = 1;
    private static final long MASK_UPDATED_CONFIG = 1 << 1;
    private static final long MASK_BUFFER_ACTION_STAGE = 1 << 2;
    private static final long MASK_HIGHER_PRIORITY_CONNECTION = 1 << 3;
    private static final long MASK_AUTO_RESET = 1 << 4;
    private static final long MASK_OBSERVERS_CHANGED = 1 << 5;

    private final BlockStreamingObs streamingObs;

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
            @NonNull final BlockNodeConfigService blockNodeConfigService,
            @NonNull final BlockStreamingObs streamingObs) {
        super(configProvider);
        this.blockBufferService = requireNonNull(blockBufferService, "blockBufferService must not be null");
        this.blockStreamMetrics = requireNonNull(blockStreamMetrics, "blockStreamMetrics must not be null");
        this.selfNodeId = requireNonNull(networkInfo, "networkInfo must not be null")
                .selfNodeInfo()
                .nodeId();
        this.blockingIoExecutorSupplier =
                requireNonNull(blockingIoExecutorSupplier, "Blocking I/O executor supplier is required");
        this.clientFactory = new BlockNodeClientFactory();
        this.blockNodeConfigService = requireNonNull(blockNodeConfigService, "Block node config service is required");
        this.streamingObs = requireNonNull(streamingObs);

        blockingIoExecutorRef.set(blockingIoExecutorSupplier.get());
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

        final ExecutorService blockingIoExecutor = blockingIoExecutorRef.getAndSet(null);
        if (blockingIoExecutor != null) {
            blockingIoExecutor.shutdownNow();
        }

        blockNodeConfigService.shutdown();
        blockBufferService.shutdown();

        // clear connection monitor thread reference
        connectionMonitorThreadRef.set(null);

        for (final BlockNode node : nodes.values()) {
            node.onTerminate(CloseReason.SHUTDOWN);
        }

        activeStreamingConnectionRef.set(null);
        activeObservingConnections.clear();

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
        final String jvmId = ConnectionId.jvmId();
        logger.info("Starting block node connection manager (jvmId: {})...", jvmId.isEmpty() ? "unavailable" : jvmId);

        if (blockingIoExecutorRef.get() == null) {
            /*
            Why the null check? We initialize the blocking I/O executor in the constructor by calling the supplier,
            but an instance of the connection manager can be shutdown and technically can be restarted. During the
            shutdown process, the executor is also shutdown (and set to null) so if the manager was started again we
            need to get another instance from the blocking I/O executor from the supplier.
             */
            blockingIoExecutorRef.compareAndSet(null, blockingIoExecutorSupplier.get());
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
     * Checks whether this node currently has an active streaming connection to a block node.
     *
     * @return true if this node is actively streaming to a block node
     */
    public boolean hasActiveStreamingConnection() {
        return isConnectionManagerActive.get() && activeStreamingConnectionRef.get() != null;
    }

    /**
     * Selects the next available block node based on priority.
     * It will skip over any nodes that are already in retry or have a lower priority than the current active connection.
     *
     * @param availableBlockNodes list of available block nodes to select from
     * @return the next available block node
     */
    private @Nullable BlockNode getNextPriorityStreamingBlockNode(@NonNull final List<BlockNode> availableBlockNodes) {
        requireNonNull(availableBlockNodes, "Available block nodes list is required");
        logger.debug("Searching for new block node connection based on node priorities");

        final SortedMap<Integer, List<BlockNode>> priorityGroups = availableBlockNodes.stream()
                .collect(Collectors.groupingBy(node -> node.configuration().priority(), TreeMap::new, toList()));

        final List<NodeCandidate> globalLowestAheadCandidates = new ArrayList<>();
        long globalLowestWantedBlock = Long.MAX_VALUE;

        for (final Map.Entry<Integer, List<BlockNode>> entry : priorityGroups.entrySet()) {
            final int priority = entry.getKey();
            final List<BlockNode> nodesInGroup = entry.getValue();
            final GroupSelectionOutcome outcome;
            try {
                outcome = findAvailableStreamingNode(nodesInGroup);
            } catch (final Exception e) {
                logger.warn("Error encountered while trying to find available node in priority group {}", priority, e);
                continue;
            }

            if (outcome == null) {
                logger.debug("No available node found in priority group {}", priority);
                continue;
            }

            if (!outcome.inRangeCandidates().isEmpty()) {
                logger.debug("Found in-range available node in priority group {}", priority);
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
                "All groups only had ahead candidates. Selecting from global lowest wantedBlock: {}",
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
            try {
                svcConnection.initialize();
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
    private @Nullable GroupSelectionOutcome findAvailableStreamingNode(@NonNull final List<BlockNode> nodes) {
        requireNonNull(nodes, "nodes must not be null");

        if (nodes.isEmpty()) {
            return null;
        }

        // collect the results and filter out nodes that either are unavailable or nodes that require a block we don't
        // have available in the buffer
        final long earliestAvailableBlock = blockBufferService.getEarliestAvailableBlockNumber();
        final long latestProducedBlock = blockBufferService.getLastBlockNumberProduced();
        final List<NodeCandidate> eligibleCandidates = new ArrayList<>();
        final Map<BlockNode, BlockNodeStatus> results = fetchBlockNodeStatus(nodes);

        for (final Map.Entry<BlockNode, BlockNodeStatus> entry : results.entrySet()) {
            final BlockNode node = entry.getKey();
            final BlockNodeEndpoint serviceEndpoint = node.configuration().serviceEndpoint();
            final BlockNodeStatus status = entry.getValue();

            if (!status.wasReachable()) {
                logger.info(
                        "[{}:{}] Block node is not a candidate for streaming (reason: unreachable/timeout)",
                        serviceEndpoint.host(),
                        serviceEndpoint.port());
                continue;
            }

            final long wantedBlock = status.nextExpectedBlock();

            if (isBlockNodeWithinRange(node, wantedBlock, earliestAvailableBlock, latestProducedBlock)) {
                eligibleCandidates.add(new NodeCandidate(node, wantedBlock));
            }
        }

        if (eligibleCandidates.isEmpty()) {
            return null;
        }

        if (latestProducedBlock == -1) {
            // Startup case: treat all reachable candidates as immediately streamable.
            return new GroupSelectionOutcome(eligibleCandidates, List.of(), Long.MAX_VALUE);
        }

        final List<NodeCandidate> inRangeCandidates = eligibleCandidates.stream()
                .filter(c -> c.wantedBlock() <= latestProducedBlock)
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
    public void notifyConnectionClosed(@NonNull final BlockNodePublisherConnection connection) {
        // Remove from active connection if it is the current active
        if (connection instanceof final BlockNodeStreamingConnection streamingConnection) {
            activeStreamingConnectionRef.compareAndSet(streamingConnection, null);
        } else if (connection instanceof final BlockNodeObservingConnection observingConnection) {
            activeObservingConnections.remove(observingConnection.blockNode(), observingConnection);
        }

        final BlockNodeEndpoint streamingEndpoint = connection.configuration().streamingEndpoint();
        final BlockNode node = nodes.get(streamingEndpoint);

        // regardless of whether the node is in the local node map, always notify the block buffer of the disconnect
        blockBufferService.notifyBlockNodeDisconnected(streamingEndpoint);

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
    public void notifyConnectionActive(@NonNull final BlockNodePublisherConnection connection) {
        final BlockNodeConfiguration config = connection.configuration();
        final BlockNode node = nodes.get(config.streamingEndpoint());

        if (node == null) {
            logger.warn(
                    "{} Connection is not associated with a known block node; ignoring open notification and closing connection",
                    connection);
            connection.close(CloseReason.INTERNAL_ERROR, true, EndStream.Code.ERROR);
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
                } catch (final Exception e) {
                    logger.warn("Error caught in connection monitor loop; continuing", e);
                }

                // The wait is deliberately outside the block above: if the connectivity check throws, the
                // loop must still back off rather than spin and flood the log.
                try {
                    Thread.sleep(bncConfig().connectionMonitorCheckIntervalMillis());
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Connection monitor loop was interrupted; continuing", e);
                } catch (final Exception e) {
                    logger.warn("Error caught while backing off in connection monitor loop; continuing", e);
                }
            }
        }
    }

    /**
     * Determines what type of criteria should be applied when selecting block nodes to stream to.
     */
    sealed interface NodeSelectionCriteria permits AnyCriteria, MinimumPriorityCriteria {}

    /**
     * No specific criteria exists - any block node that is eligible for streaming is a candidate.
     */
    record AnyCriteria() implements NodeSelectionCriteria {}

    /**
     * Only block nodes that have an equal or higher priority than the specified priority are candidates for streaming.
     *
     * @param priority the minimum priority
     */
    record MinimumPriorityCriteria(int priority) implements NodeSelectionCriteria {}

    /**
     * Checks if the current state of connectivity is healthy and if not, it will attempt to take corrective actions.
     */
    private void updateConnectionIfNeeded() {
        // record the latest number of active connections
        blockStreamMetrics.recordActiveStreamingConnectionCount(globalActiveStreamingConnectionCount.get());
        blockStreamMetrics.recordActiveObservingConnectionCount(globalActiveObservingConnectionCount.get());

        final Instant now = Instant.now();
        final BlockNodeStreamingConnection activeConnection = activeStreamingConnectionRef.get();

        // Re-emit the active connection IP (0 when there is no active connection) so the gauge does not
        // remain stale after the active connection closes (e.g. when all block node configuration is removed).
        blockStreamMetrics.recordActiveConnectionIp(
                activeConnection == null ? 0L : activeConnection.ipV4AddressAsInt());

        checkActiveStreamingConnectionStalled(now, activeConnection);

        CloseReason closeReason = CloseReason.UNKNOWN; // close reason for the current streaming connection (if exists)
        NodeSelectionCriteria criteria = new AnyCriteria();
        long changes = NO_CHANGES;

        if (isMissingActiveStreamingConnection(activeConnection)) {
            changes |= MASK_NO_ACTIVE_CONNECTION;
        }
        if (isConfigUpdated()) {
            changes |= MASK_UPDATED_CONFIG;
            closeReason = CloseReason.CONFIG_UPDATE;
        }
        if (isBufferUnhealthy()) {
            changes |= MASK_BUFFER_ACTION_STAGE;
            closeReason = CloseReason.BUFFER_SATURATION;
        }
        if (isHigherPriorityNodeAvailable(activeConnection)) {
            changes |= MASK_HIGHER_PRIORITY_CONNECTION;
            criteria =
                    new MinimumPriorityCriteria(activeConnection.configuration().priority() - 1);
            closeReason = CloseReason.HIGHER_PRIORITY_FOUND;
        }
        if (isActiveConnectionAutoReset(now, activeConnection)) {
            changes |= MASK_AUTO_RESET;
            closeReason = CloseReason.PERIODIC_RESET;
        }
        if (isObservingUpdateNeeded()) {
            changes |= MASK_OBSERVERS_CHANGED;
        }

        if (changes != NO_CHANGES) {
            final long previousChanges = latestChanges;
            latestChanges = changes;
            // suppress verbose change logging if there are no changes in what was detected since the last check
            // this will make it so we only log the changes once instead of every time the monitor fires
            // otherwise, we could spam the logs over and over with the same thing when we can't find a connection
            suppressNoisyMonitorLogging = previousChanges == changes;

            logDetectedChanges(changes);

            boolean updated = false;

            if (changes == MASK_OBSERVERS_CHANGED) {
                // the only trigger is a lack of observers, so just handle that
                updated |= refreshObservingConnections();
            } else {
                /*
                There are other triggers aside from too few observers. In this case, refresh everything - streaming and
                observing connections. Always force a streaming connection update if there is no active streaming
                connection.

                TODO: Be smarter about whether we need to update the streaming connection.
                For example, if the streaming connection is alive and well, but buffer saturation is growing, it may be
                due to a lack of observers. In this case, we may just need to create more observers. But, for now, we
                will take the naive route and just trigger an update for everything. This may cause some extra churn,
                but the path forward isn't straightforward so defer it to a later time.
                 */

                final boolean forceStreamingUpdate = (changes & MASK_NO_ACTIVE_CONNECTION) != 0;
                updated |= selectNewStreamingBlockNode(forceStreamingUpdate, criteria, closeReason, activeConnection);
                updated |= refreshObservingConnections();
            }

            if (updated) {
                // if we've made updates to the connections, disable suppressing verbose change logs
                suppressNoisyMonitorLogging = false;
                // also reset the latest change flags to 0
                latestChanges = NO_CHANGES;
            }
        } else {
            logger.trace("Block node connectivity is healthy; no corrective action needed at this time");
        }
    }

    /**
     * Write a human-readable log about what changed. If INFO logging is not enabled or verbose change logging is
     * disabled, then the log will not be written.
     *
     * @param changes the changes detected
     */
    private void logDetectedChanges(final long changes) {
        if (suppressNoisyMonitorLogging || !logger.isInfoEnabled()) {
            return;
        }

        final StringBuilder sb = new StringBuilder("Streaming connection update requested (reason:");
        if ((changes & MASK_NO_ACTIVE_CONNECTION) != 0) {
            sb.append(" missing-active-connection");
        }
        if ((changes & MASK_UPDATED_CONFIG) != 0) {
            sb.append(" config-updated");
        }
        if ((changes & MASK_BUFFER_ACTION_STAGE) != 0) {
            sb.append(" buffer-unhealthy");
        }
        if ((changes & MASK_HIGHER_PRIORITY_CONNECTION) != 0) {
            sb.append(" higher-priority-connection-found");
        }
        if ((changes & MASK_AUTO_RESET) != 0) {
            sb.append(" auto-reset-active-connection");
        }
        if ((changes & MASK_OBSERVERS_CHANGED) != 0) {
            sb.append(" observers-update-needed");
        }
        sb.append(")");

        logger.info("{}", sb);
    }

    /**
     * Checks if the number of active observing connections is not equal to the minimum number of acknowledgements
     * required, therefore an update is needed to get the observer count in sync.
     *
     * @return true if an update to the observing connections is needed, else false
     */
    private boolean isObservingUpdateNeeded() {
        final int minAcksRequired = bufferConfig().minAcksRequiredPerBlock();
        final int activeObservers = activeObservingConnections.size();

        if (minAcksRequired <= 1) {
            // multi-acknowledgements are not enabled, return false if there are no active observers
            // otherwise return true so the active observers get pruned
            return activeObservers != 0;
        }
        return activeObservers != minAcksRequired;
    }

    /**
     * Simple test to determine if the specified active connection exists or not.
     *
     * @param activeConnection the connection to test
     * @return true if the connection is null, else false
     */
    private boolean isMissingActiveStreamingConnection(@Nullable final BlockNodeStreamingConnection activeConnection) {
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
                                    configProvider(),
                                    newNodeConfig,
                                    globalActiveStreamingConnectionCount,
                                    globalActiveObservingConnectionCount,
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
        final BlockBufferSaturationInfo latestSaturationInfo = blockBufferService.latestBufferSaturationInfo();
        final BlockBufferSaturationInfo previousSaturationInfo = bufferSaturationRef.getAndSet(latestSaturationInfo);

        /*
        There are multiple facets that can influence whether the block buffer is unhealthy, but not all of those facets
        can be aided in switching connections. For example, if saturation is high due to in-progress blocks, switching
        to another block node will have no impact since the issue is local to the consensus node and is not the result
        of a block node. So, we need to check the cause of the saturation to know if we need to take action or not.
         */

        if (latestSaturationInfo == null) {
            return false;
        }

        /*
        If the most recent saturation information indicates that we are currently at or above the action stage, it will
        typically imply that we want to take some sort of action such as switching to a different block node. However,
        we only want to take action if the saturation action is steady or growing. If the saturation is going down, it
        likely means we are recovering, and therefore we don't want to take action such as switching block nodes.
         */

        if (latestSaturationInfo.unackedBlocksSaturation().isAtActionStage()) {
            if (previousSaturationInfo == null
                    || latestSaturationInfo.unackedBlocksSaturation().percentage()
                            >= previousSaturationInfo.unackedBlocksSaturation().percentage()) {
                return true;
            }
        }

        if (latestSaturationInfo.unackedBytesSaturation().isAtActionStage()) {
            if (previousSaturationInfo == null
                    || latestSaturationInfo.unackedBytesSaturation().percentage()
                            >= previousSaturationInfo.unackedBytesSaturation().percentage()) {
                return true;
            }
        }

        return false; // no action needed at this time
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
     */
    private void checkActiveStreamingConnectionStalled(
            @NonNull final Instant now, @Nullable final BlockNodeStreamingConnection activeConnection) {
        if (activeConnection == null) {
            return;
        }

        final long stalledConnectionThresholdMillis = bncConfig().connectionStallThresholdMillis();
        final long lastHeartbeatTimestamp =
                activeConnection.connectionStatistics().lastHeartbeatMillis();

        if (lastHeartbeatTimestamp != -1) {
            final long deltaMillis = now.toEpochMilli() - lastHeartbeatTimestamp;
            if (deltaMillis >= stalledConnectionThresholdMillis) {
                logger.warn(
                        "{} Active connection is slow/stalled (lastHeartbeat: {}, threshold: {}ms, observed: {}ms)",
                        activeConnection,
                        lastHeartbeatTimestamp,
                        stalledConnectionThresholdMillis,
                        deltaMillis);
            }
        }
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
     * Refreshes the set of observing connections.
     *
     * @return true if the observing connections were updated, else false
     */
    private boolean refreshObservingConnections() {
        final int numAcksRequired = bufferConfig().minAcksRequiredPerBlock();
        final int activeObserverCount = activeObservingConnections.size();
        if (numAcksRequired == 1) {
            /*
            We only require 1 acknowledgement, which is the default case. In this scenario, we only need the streaming
            connection and no additional observers. This is because the streaming connection also accepts
            acknowledgements and is thus an "observer" in its own right. The only thing we need to _maybe_ do is prune
            any existing observing connections.
             */
            if (activeObservingConnections.isEmpty()) {
                return false;
            } else {
                logger.info(
                        "Detected {} active observing connection(s), but the number of required ACKs is 1; all observers will be pruned",
                        activeObserverCount);
                return pruneExcessObservers(activeObserverCount).updated;
            }
        }

        /*
        At this point, we enter multi-acknowledgement land. Here we will need additional observing connections. We base
        the number of observing connections on the minimum number of acknowledgements required. If the minimum number of
        acknowledgements is 3, for example, ideally we would create 3 additional observing connections. Doing this would
        give us 4 acknowledgement sources: 1 from the active streaming connection, and 3 from the observing connections.
        Technically, this means we have more sources than needed, but it offers us a failsafe mechanism in the event one
        of the sources fails. In this case, we would still have 3 sources to meet our minimum acknowledgement count
        requirement.
         */

        if (numAcksRequired < activeObserverCount) {
            // we have too many observers, so shed some of them
            final int numObserversToPrune = activeObserverCount - numAcksRequired;
            logger.info(
                    "Detected {} active observing connection(s), which is higher than the ideal count of {}; attempting to shed {} connection(s)",
                    activeObserverCount,
                    numAcksRequired,
                    numObserversToPrune);
            return pruneExcessObservers(numObserversToPrune).updated;
        } else if (numAcksRequired == activeObserverCount) {
            logger.trace(
                    "Detected {} active observing connection(s), which matches the ideal count of {}; no action is needed",
                    activeObserverCount,
                    numAcksRequired);
            return false;
        }

        /*
        If we get to this point, it means we don't have enough observers. Ideally we have a number of observers equal
        to the number of minimum ACKs required, however it is not required. Since there is a streaming connection that
        also receives acknowledgements, it is another node to push our acknowledgement high watermark further. So, if we
        don't have the ideal number of observers, if we have the minimum number of targets (streaming + observing) then
        maybe defer trying to get the ideal number of connections so we aren't trying to connect too frequently.
         */

        final int numObserversToCreate = numAcksRequired - activeObserverCount;
        if (numObserversToCreate == 1 && activeStreamingConnectionRef.get() != null) {
            final Instant lastIdealObserversAttempt = idealObserversLastAttemptTimestampRef.get();
            final int idealObserversCoolDownSeconds = bncConfig().idealObserversCoolDownSeconds();
            final Instant cutoffTimestamp = lastIdealObserversAttempt.plusSeconds(idealObserversCoolDownSeconds);
            if (cutoffTimestamp.isAfter(Instant.now())) {
                // not enough time has elapsed; skip trying to establish the ideal number of connections for now
                return false;
            } else {
                // enough time has elapsed since the last attempt to establish the ideal number of observers
                // proceed with the attempt
                idealObserversLastAttemptTimestampRef.set(Instant.now());
            }
        }

        logger.info(
                "Not enough observing connections active; attempting to establish {} additional connection(s)",
                numObserversToCreate);

        final List<BlockNode> candidates =
                nodes.values().stream().filter(BlockNode::isObservingCandidate).toList();
        final Map<BlockNode, BlockNodeStatus> results = fetchBlockNodeStatus(candidates);
        final SortedBlockNodes sortedBlockNodes = sortBlockNodes(results);
        final List<BlockNode> rankedBlockNodes = sortedBlockNodes.rankedInRangeNodes.stream()
                .map(BlockNodeResult::node)
                .toList();

        return maybeUpdateObservingConnections(rankedBlockNodes, numAcksRequired).updated;
    }

    /**
     * Collection of block nodes that are sorted into buckets.
     *
     * @param rankedInRangeNodes the in-range block nodes, sorted by preference
     * @param outOfRangeNodes the block nodes that are out of range
     * @param unreachableNodes the block nodes that were unreachable
     */
    record SortedBlockNodes(
            @NonNull List<BlockNodeResult> rankedInRangeNodes,
            @NonNull Set<BlockNodeResult> outOfRangeNodes,
            @NonNull Set<BlockNode> unreachableNodes) {}

    /**
     * Simple holder for block node status pairs.
     *
     * @param node the block node
     * @param status the status result
     */
    record BlockNodeResult(@NonNull BlockNode node, @NonNull BlockNodeStatus status) {}

    /**
     * Information related to the outcome of an attempt to update observing connections.
     *
     * @param updated true if one or more observing connections were updated, else false
     * @param newObservers set of new block nodes that are now being observed
     * @param existingObservers set of block nodes that were previously being observed and continue to be
     * @param prunedObservers set of block nodes that are no longer being observed
     */
    record ObservingConnectionsUpdateResult(
            boolean updated,
            @NonNull Set<BlockNode> newObservers,
            @NonNull Set<BlockNode> existingObservers,
            @NonNull Set<BlockNode> prunedObservers) {}

    /**
     * Prune excess observing connections.
     *
     * @param numToPrune the number of connections to prune
     * @return the outcome of the pruning operation
     */
    private ObservingConnectionsUpdateResult pruneExcessObservers(final int numToPrune) {
        if (numToPrune < 1) {
            return new ObservingConnectionsUpdateResult(false, Set.of(), activeObservingConnections.keySet(), Set.of());
        }

        final Iterator<Map.Entry<BlockNode, BlockNodeObservingConnection>> it =
                activeObservingConnections.entrySet().iterator();
        final Set<BlockNode> prunedObservers = new HashSet<>();

        while (it.hasNext()) {
            final Map.Entry<BlockNode, BlockNodeObservingConnection> entry = it.next();
            final BlockNode node = entry.getKey();
            final BlockNodeObservingConnection connection = entry.getValue();
            final BlockNodeEndpoint streamingEndpoint = node.configuration().streamingEndpoint();
            logger.info("Pruning observer for node {}:{}", streamingEndpoint.host(), streamingEndpoint.port());
            try {
                connection.close(CloseReason.OBSOLETE, true, EndStream.Code.RESET);
            } catch (final Exception e) {
                // suppress any failures on close
                logger.debug("{} Failed to close observer - suppressing", connection, e);
            } finally {
                it.remove();
                prunedObservers.add(node);
            }

            if (numToPrune == prunedObservers.size()) {
                break;
            }
        }

        return new ObservingConnectionsUpdateResult(!prunedObservers.isEmpty(), Set.of(), Set.of(), prunedObservers);
    }

    /**
     * Updates the set of observing connections, if necessary.
     *
     * @param rankedNodes the available block nodes to observe, in order of preference
     * @param numObserversWanted the number of observing connection desired
     * @return result of the update attempt
     */
    private ObservingConnectionsUpdateResult maybeUpdateObservingConnections(
            @NonNull final List<BlockNode> rankedNodes, final int numObserversWanted) {
        if (numObserversWanted < 1) {
            return new ObservingConnectionsUpdateResult(false, Set.of(), activeObservingConnections.keySet(), Set.of());
        }

        final List<BlockNode> blockNodes = new ArrayList<>(rankedNodes);
        final Iterator<BlockNode> nodesIt = blockNodes.iterator();
        final List<BlockNode> observers = new ArrayList<>();
        final Set<BlockNode> newObservers = new HashSet<>();
        final Set<BlockNode> existingObservers = new HashSet<>();
        boolean updated = false;

        if (!activeObservingConnections.isEmpty()) {
            // include any active observers so we can avoid closing healthy observers for no good reason
            for (final Map.Entry<BlockNode, BlockNodeObservingConnection> entry :
                    activeObservingConnections.entrySet()) {
                final BlockNode node = entry.getKey();
                final BlockNodeObservingConnection connection = entry.getValue();

                if (connection.isActive()) {
                    observers.add(node);
                    existingObservers.add(node);
                }

                if (observers.size() == numObserversWanted) {
                    break;
                }
            }
        }

        if (numObserversWanted != observers.size()) {
            while (nodesIt.hasNext()) {
                final BlockNode node = nodesIt.next();
                if (activeObservingConnections.containsKey(node)) {
                    // this block node is already acting as an observer so we don't need to do anything else
                    observers.add(node);
                    existingObservers.add(node);
                } else {
                    // found a new observer block node to connect to
                    if (tryNewObservingConnection(node)) {
                        observers.add(node);
                        newObservers.add(node);
                        updated = true;
                    }
                }

                if (observers.size() == numObserversWanted) {
                    break;
                }
            }
        }

        final Set<BlockNode> prunedObservers = new HashSet<>();

        // shutdown any observers not known during this iteration
        final Iterator<Entry<BlockNode, BlockNodeObservingConnection>> obsIt =
                activeObservingConnections.entrySet().iterator();
        while (obsIt.hasNext()) {
            final Map.Entry<BlockNode, BlockNodeObservingConnection> entry = obsIt.next();
            final BlockNode node = entry.getKey();

            if (!observers.contains(node)) {
                // found an "old" observer that isn't needed - close it
                final BlockNodeObservingConnection connection = entry.getValue();
                try {
                    connection.close(CloseReason.NEW_CONNECTION, true, EndStream.Code.RESET);
                } catch (final Exception e) {
                    // suppress any failures on close
                    logger.debug("{} Failed to close observer - suppressing", connection, e);
                } finally {
                    updated = true;
                    prunedObservers.add(node);
                    obsIt.remove();
                }
            }
        }

        if (numObserversWanted > observers.size()) {
            // we don't have enough block node targets to meet the desired observer count
            logger.warn(
                    "Not enough observing connections were created (wanted: {}, new: {}, totalActive: {})",
                    numObserversWanted,
                    newObservers.size(),
                    activeObservingConnections.size());
        } else {
            // we have enough observers, yay!
            logger.debug(
                    "Desired number of observing connections reached (wanted: {}, new: {}, existing: {}, pruned: {}, totalActive: {})",
                    numObserversWanted,
                    newObservers.size(),
                    existingObservers.size(),
                    prunedObservers.size(),
                    activeObservingConnections.size());
        }

        return new ObservingConnectionsUpdateResult(updated, newObservers, existingObservers, prunedObservers);
    }

    /**
     * Attempts to update the active streaming connection to be to the specified block node. If there was an existing
     * active connection (as specified) it will be closed at the next block boundary. Lastly, if there was an observing
     * connection to the block node we are starting to stream to, the observing connection will be closed after the
     * streaming connection is successfully established.
     *
     * @param streamingNode the block node to stream to
     * @param closeReason the close reason to assign to the existing streaming connection - if one exists
     * @param activeConnection the active connection being replaced
     * @return true if a new streaming connection was established, else false
     */
    private boolean tryUpdateNewStreamingConnection(
            @NonNull final BlockNode streamingNode,
            @NonNull final CloseReason closeReason,
            @Nullable final BlockNodeStreamingConnection activeConnection) {
        final BlockNodeEndpoint endpoint = streamingNode.configuration().streamingEndpoint();
        logger.info(
                "Selected new block node for streaming: {}:{} (wantedBlock: {})",
                endpoint.host(),
                endpoint.port(),
                streamingNode.wantedBlock());

        final BlockNodeStreamingConnection newConnection = new BlockNodeStreamingConnection(
                configProvider(),
                streamingNode,
                this,
                blockBufferService,
                blockStreamMetrics,
                blockingIoExecutorSupplier.get(),
                streamingNode.wantedBlock(),
                clientFactory,
                selfNodeId,
                streamingObs);

        try {
            newConnection.initialize();
        } catch (final Exception e) {
            logger.warn("{} Failed to initialize streaming connection", newConnection, e);
            blockStreamMetrics.recordConnectionCreateFailure(newConnection.connectionType());
            newConnection.close(CloseReason.INTERNAL_ERROR, true, null);
            return false; // exit, try again later or with a different node
        }

        newConnection.updateConnectionState(ConnectionState.ACTIVE);
        activeStreamingConnectionRef.set(newConnection);

        if (activeConnection != null) {
            activeConnection.closeAtBlockBoundary(closeReason);
        }

        // if this new node was previously an observer, close the observer
        // do this AFTER successfully establishing the streaming connection
        final BlockNodeObservingConnection observingConnection = activeObservingConnections.get(streamingNode);
        if (observingConnection != null) {
            try {
                observingConnection.close(CloseReason.NEW_CONNECTION, true, EndStream.Code.RESET);
            } catch (final Exception e) {
                logger.warn("{} Failed to close observer - suppressing", observingConnection, e);
            }
        }

        // set the global cool down so we don't try to switch connections too frequently
        final int coolDownSeconds = bncConfig().globalCoolDownSeconds();
        globalCoolDownTimestampRef.set(Instant.now().plusSeconds(coolDownSeconds));

        return true;
    }

    /**
     * Attempts to create a new observing connection to the specified block node.
     *
     * @param node the block node to create an observing connection to
     * @return true if the observing connection was successfully established, else false
     */
    private boolean tryNewObservingConnection(@NonNull final BlockNode node) {
        final BlockNodeEndpoint endpoint = node.configuration().streamingEndpoint();
        logger.info("Selected new block node for observing: {}:{}", endpoint.host(), endpoint.port());

        final BlockNodeObservingConnection newConnection = new BlockNodeObservingConnection(
                configProvider(),
                node,
                this,
                blockBufferService,
                blockStreamMetrics,
                blockingIoExecutorSupplier.get(),
                clientFactory,
                selfNodeId);

        try {
            newConnection.initialize();
        } catch (final Exception e) {
            logger.warn("{} Failed to initialize observing connection", newConnection, e);
            blockStreamMetrics.recordConnectionCreateFailure(newConnection.connectionType());
            newConnection.close(CloseReason.INTERNAL_ERROR, true, null);
            return false;
        }

        boolean wasSuccessful = true;
        final BlockNodeObservingConnection oldConnection = activeObservingConnections.put(node, newConnection);
        // activating the connection should happen AFTER the connection has been inserted into the connection map
        // this is to avoid a scenario where the connection fails upon activation, and we insert a dead-on-arrival
        // connection into the tracking map
        try {
            newConnection.updateConnectionState(ConnectionState.ACTIVE);
        } catch (final Exception e) {
            logger.warn("{} Failed to activate observer - suppressing", newConnection, e);
            activeObservingConnections.remove(node, newConnection);
            blockStreamMetrics.recordConnectionCreateFailure(newConnection.connectionType());
            // depending on the failure we may or may not be able to send an EndStream message, so err on the side of
            // caution and pass in an EndStream code so we attempt to send it if possible
            newConnection.close(CloseReason.INTERNAL_ERROR, true, EndStream.Code.ERROR);
            wasSuccessful = false;
        }

        // regardless of whether the new connection failed or not, if there was another connection this new one meant
        // to replace, proceed with cleaning it up. granted, if we've done our job well this shouldn't be possible
        if (oldConnection != null) {
            try {
                oldConnection.close(CloseReason.NEW_CONNECTION, true, EndStream.Code.RESET);
            } catch (final Exception e) {
                // suppress any failures on close
                logger.warn("{} Failed to close observer - suppressing", oldConnection, e);
            }
        }

        return wasSuccessful;
    }

    /**
     * Sorts the provided block node (and their related status check results) into one of three buckets: nodes in range,
     * nodes out of range, and nodes that are unreachable. Furthermore, the nodes that are in range are sorted based on
     * latency data collected during the status check.
     *
     * @param results the block node status checks
     * @return the sorted and ranked block nodes
     */
    private @NonNull SortedBlockNodes sortBlockNodes(@NonNull final Map<BlockNode, BlockNodeStatus> results) {
        final long latestBlockProduced = blockBufferService.getLastBlockNumberProduced();
        final long earliestBlockAvailable = blockBufferService.getEarliestAvailableBlockNumber();

        // filter out the block nodes that are not within range of this consensus node and sort them by latency
        final SortedMap<Long, Set<BlockNodeResult>> nodesInRangeByLatency = new TreeMap<>();
        final Set<BlockNodeResult> nodesOutOfRange = new HashSet<>();
        final Set<BlockNode> nodesUnreachable = new HashSet<>();

        results.forEach((node, status) -> {
            if (!status.wasReachable()) {
                nodesUnreachable.add(node);
            } else if (isBlockNodeWithinRange(
                    node, status.nextExpectedBlock(), earliestBlockAvailable, latestBlockProduced)) {
                nodesInRangeByLatency
                        .computeIfAbsent(status.latencyMillis(), _ -> new HashSet<>())
                        .add(new BlockNodeResult(node, status));
            } else {
                nodesOutOfRange.add(new BlockNodeResult(node, status));
            }
        });

        // convert the in-range nodes into a sorted flat list
        final List<BlockNodeResult> rankedBlockNodes = new ArrayList<>();
        nodesInRangeByLatency.forEach((_, inRangeNodes) -> rankedBlockNodes.addAll(inRangeNodes));

        return new SortedBlockNodes(rankedBlockNodes, nodesOutOfRange, nodesUnreachable);
    }

    /**
     * Checks if the specified block node's wanted block is within range of this consensus node. If the block is not
     * within range, the block node will be placed in cool down.
     *
     * @param node the block node being checked
     * @param wantedBlock which block the block node wants next
     * @param earliestAvailableBlock the earliest available block locally buffered
     * @param latestBlock the latest available block locally buffered
     * @return true if the wanted block is within range, else false
     */
    private boolean isBlockNodeWithinRange(
            final BlockNode node, final long wantedBlock, final long earliestAvailableBlock, final long latestBlock) {
        final BlockNodeEndpoint streamingEndpoint = node.configuration().streamingEndpoint();

        /*
        There is a scenario in which this consensus node may not have any blocks loaded. For example, this node may
        be initializing for the first time or the node may have restarted but there aren't any buffered blocks that
        were persisted. In either case, upon startup the node will not be aware of any blocks and thus the last
        produced block will be marked as -1. In this case, we will permit connecting to any block node, as long as
        it is reachable. Once this node joins the network and is able to start producing blocks, those new blocks
        will be streamed to the block node. If it turns out the block node is behind, or ahead, of the consensus
        node, then existing reconnect operations will engage to sort things out.
        */

        if (latestBlock == -1) {
            logger.info(
                    "[{}:{}] Block node is maybe within range of this consensus node (wantedBlock: {}, blocksAvailable: <unknown>)",
                    streamingEndpoint.host(),
                    streamingEndpoint.port(),
                    wantedBlock);
            return true;
        } else if (wantedBlock < -1) {
            node.applyCoolDown(new BlockNodeOutOfRange(wantedBlock));
            logger.info(
                    "[{}:{}] Block node is not a within range of this consensus node (reason: block out of range (wantedBlock: {}))",
                    streamingEndpoint.host(),
                    streamingEndpoint.port(),
                    wantedBlock);
            return false;
        } else if (wantedBlock != -1 && wantedBlock < earliestAvailableBlock) {
            final long numBlocksBehind = earliestAvailableBlock - wantedBlock;
            node.applyCoolDown(new BlockNodeOutOfRange(numBlocksBehind));
            logger.info(
                    "[{}:{}] Block node is not a within range of this consensus node (reason: block out of range (wantedBlock: {}, blocksAvailable: {}-{}))",
                    streamingEndpoint.host(),
                    streamingEndpoint.port(),
                    wantedBlock,
                    earliestAvailableBlock,
                    latestBlock);
            return false;
        }

        logger.info(
                "[{}:{}] Block node is within range of this consensus node (wantedBlock: {}, blocksAvailable: {}-{})",
                streamingEndpoint.host(),
                streamingEndpoint.port(),
                wantedBlock,
                earliestAvailableBlock,
                latestBlock);

        return true;
    }

    private @NonNull Map<BlockNode, BlockNodeStatus> fetchBlockNodeStatus(@NonNull final List<BlockNode> nodes) {
        requireNonNull(nodes, "nodes must not be null");
        if (nodes.isEmpty()) {
            return Map.of();
        }

        final ExecutorService blockingIoExecutor = blockingIoExecutorRef.get();
        // for status checks there are two timeouts: 1) timeout setting up the connection and 2) timeout for calling
        // the status API itself. and since this is a batch, we will add a little padding
        final long batchTimeoutMillis = connectionManagementTimeoutMillis() + statusTimeoutMillis() + 500;

        final List<BlockNodeConnectionManager.RetrieveBlockNodeStatusTask> tasks = new ArrayList<>();
        for (final BlockNode node : nodes) {
            final BlockNodeServiceConnection svcConnection = new BlockNodeServiceConnection(
                    configProvider(), node.configuration(), blockingIoExecutor, clientFactory, selfNodeId);
            tasks.add(new RetrieveBlockNodeStatusTask(svcConnection));
        }

        final List<Future<BlockNodeStatus>> futures;
        try {
            futures = new ArrayList<>(blockingIoExecutor.invokeAll(tasks, batchTimeoutMillis, TimeUnit.MILLISECONDS));
        } catch (final InterruptedException _) {
            logger.warn("Interrupted while waiting for one or more block node status retrieval tasks; ignoring batch");
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (final Exception e) {
            logger.warn(
                    "Error encountered while waiting for one or more block node retrieval tasks to complete; ignoring batch",
                    e);
            return Map.of();
        }

        if (nodes.size() != futures.size()) {
            // this should never happen, but we will be defensive and check anyway
            logger.warn(
                    "Number of candidates ({}) does not match the number of tasks submitted ({}); ignoring batch",
                    nodes.size(),
                    futures.size());
            return Map.of();
        }

        final Map<BlockNode, BlockNodeStatus> results = new HashMap<>();

        for (int i = 0; i < nodes.size(); ++i) {
            final BlockNode node = nodes.get(i);
            final BlockNodeEndpoint serviceEndpoint = node.configuration().serviceEndpoint();
            final Future<BlockNodeStatus> future = futures.get(i);
            final BlockNodeStatus status = switch (future.state()) {
                case SUCCESS -> {
                    final BlockNodeStatus bns = future.resultNow();
                    if (bns == null) {
                        logger.warn(
                                "[{}:{}] Retrieving block node status was successful, but null returned",
                                serviceEndpoint.host(),
                                serviceEndpoint.port());
                        // we don't have any information so mark it unreachable... hopefully this never happens
                        yield BlockNodeStatus.notReachable();
                    } else {
                        logger.debug(
                                "[{}:{}] Successfully retrieved block node status",
                                serviceEndpoint.host(),
                                serviceEndpoint.port());
                        yield bns;
                    }
                }
                case FAILED -> {
                    final Throwable error = future.exceptionNow();
                    final FailureType failureType = FailureType.findFailureType(error);
                    if (failureType.isCommonFailure()) {
                        logger.warn(
                                "[{}:{}] Failed to retrieve block node status (error: {})",
                                serviceEndpoint.host(),
                                serviceEndpoint.port(),
                                failureType);
                    } else {
                        logger.warn(
                                "[{}:{}] Failed to retrieve block node status",
                                serviceEndpoint.host(),
                                serviceEndpoint.port(),
                                error);
                    }

                    yield BlockNodeStatus.notReachable();
                }
                case CANCELLED, RUNNING -> {
                    logger.warn(
                            "[{}:{}] Timed out waiting for block node status",
                            serviceEndpoint.host(),
                            serviceEndpoint.port());
                    future.cancel(true);
                    yield BlockNodeStatus.notReachable();
                }
            };

            results.put(node, status);

            node.onServerStatusCheck(status);
        }

        return results;
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
    private boolean selectNewStreamingBlockNode(
            final boolean force,
            @NonNull final BlockNodeConnectionManager.NodeSelectionCriteria criteria,
            @NonNull final CloseReason closeReason,
            @Nullable final BlockNodeStreamingConnection activeConnection) {
        requireNonNull(criteria, "Selection criteria is required");
        pruneNodes();

        final Instant globalCoolDownTimestamp = globalCoolDownTimestampRef.get();

        if (globalCoolDownTimestamp != null && Instant.now().isBefore(globalCoolDownTimestamp) && !force) {
            if (!suppressNoisyMonitorLogging) {
                logger.info(
                        "Selecting a new block node is deferred due to global cool down until {}",
                        globalCoolDownTimestamp);
            }
            return false;
        }

        if (logger.isTraceEnabled()) {
            // log the available nodes and their connection history
            final StringBuilder sb = new StringBuilder("Block Nodes:");
            if (nodes.isEmpty()) {
                sb.append(" <none>");
            } else {
                final SortedMap<Integer, List<BlockNode>> blockNodesByPriority = new TreeMap<>();
                nodes.values().forEach(node -> {
                    final BlockNodeConfiguration cfg = node.configuration();
                    blockNodesByPriority
                            .computeIfAbsent(cfg.priority(), _ -> new ArrayList<>())
                            .add(node);
                });

                for (final Map.Entry<Integer, List<BlockNode>> entry : blockNodesByPriority.entrySet()) {
                    for (final BlockNode node : entry.getValue()) {
                        sb.append("\n");
                        node.writeInformation(sb);
                    }
                }
            }

            logger.trace("{}", sb);
        }

        // determine which nodes are candidates to connect to
        final List<BlockNode> candidates = new ArrayList<>(nodes.size());
        for (final BlockNode node : nodes.values()) {
            if (node.isStreamingCandidate()) {
                // spotless:off
                switch (criteria) {
                    case BlockNodeConnectionManager.AnyCriteria() -> candidates.add(node);
                    case BlockNodeConnectionManager.MinimumPriorityCriteria(final int minPriority) when node.configuration().priority() <= minPriority -> candidates.add(node);
                    default -> { /* don't add the node as a candidate */ }
                }
                // spotless:on
            }
        }

        if (candidates.isEmpty()) {
            if (!suppressNoisyMonitorLogging) {
                logger.info("No block node candidates found for selection criteria: {}", criteria);
            }
            return false;
        }

        final BlockNode selectedNode = getNextPriorityStreamingBlockNode(candidates);

        if (selectedNode == null) {
            if (activeConnection == null) {
                logger.warn("No block nodes available for streaming");
            } else {
                logger.debug("Tried to find another block node to connect to, but none were available");
            }
            return false;
        }

        return tryUpdateNewStreamingConnection(selectedNode, closeReason, activeConnection);
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
