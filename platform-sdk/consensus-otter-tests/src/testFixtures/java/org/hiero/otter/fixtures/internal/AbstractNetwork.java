// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CATASTROPHIC_FAILURE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZE_COMPLETE;
import static org.hiero.otter.fixtures.internal.AbstractNode.UNSET_WEIGHT;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.platform.crypto.CryptoStatic;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.security.cert.CertificateEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.assertj.core.data.Percentage;
import org.hiero.base.utility.Threshold;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.monitoring.FallenBehindStatus;
import org.hiero.consensus.test.fixtures.WeightGenerator;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.hiero.otter.fixtures.AsyncNetworkActions;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionFactory;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.helpers.Utils;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.internal.network.GeoMeshTopologyImpl;
import org.hiero.otter.fixtures.internal.network.MeshTopologyImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodeConsensusResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodeEventStreamResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodeLogResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodePcesResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodePlatformStatusResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodeReconnectResultsImpl;
import org.hiero.otter.fixtures.network.BandwidthLimit;
import org.hiero.otter.fixtures.network.BidirectionalConnection;
import org.hiero.otter.fixtures.network.GeoMeshTopologyConfiguration;
import org.hiero.otter.fixtures.network.LatencyRange;
import org.hiero.otter.fixtures.network.MeshTopologyConfiguration;
import org.hiero.otter.fixtures.network.Partition;
import org.hiero.otter.fixtures.network.Topology;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;
import org.hiero.otter.fixtures.network.TopologyConfiguration;
import org.hiero.otter.fixtures.network.UnidirectionalConnection;
import org.hiero.otter.fixtures.network.transactions.OtterTransaction;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.MultipleNodeEventStreamResults;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.MultipleNodePcesResults;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.MultipleNodeReconnectResults;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;
import org.hiero.otter.fixtures.util.OtterSavedStateUtils;

/**
 * An abstract base class for a network implementation that provides common functionality shared by the different
 * environments.
 */
public abstract class AbstractNetwork implements Network {
    /**
     * The fraction of nodes that must consider a node behind for the node to be considered behind by the network.
     */
    private static final double BEHIND_FRACTION = 0.5;

    /**
     * The state of the network.
     */
    protected enum Lifecycle {
        INIT,
        RUNNING,
        SHUTDOWN
    }

    private static final Logger log = LogManager.getLogger();

    /** The format for node identifiers in the network. */
    public static final String NODE_IDENTIFIER_FORMAT = "node-%d";

    /** The default port for gossip communication. */
    private static final int GOSSIP_PORT = 5777;

    /** The delay before a freeze transaction takes effect. */
    private static final Duration FREEZE_DELAY = Duration.ofSeconds(10L);

    /** The default timeout duration for network operations. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2L);

    private final Random random;
    private final Map<NodeId, PartitionImpl> networkPartitions = new HashMap<>();
    private final Map<ConnectionKey, Boolean> connected = new HashMap<>();
    private final Map<ConnectionKey, LatencyOverride> latencyOverrides = new HashMap<>();
    private final Map<ConnectionKey, BandwidthLimit> bandwidthOverrides = new HashMap<>();
    private final boolean useRandomNodeIds;

    private Topology currentTopology;
    protected final NetworkConfiguration networkConfiguration;

    protected Lifecycle lifecycle = Lifecycle.INIT;

    protected WeightGenerator weightGenerator = WeightGenerators.REAL_NETWORK_GAUSSIAN;

    protected Roster roster;

    @Nullable
    private PartitionImpl remainingNetworkPartition;

    private NodeId nextNodeId = NodeId.FIRST_NODE_ID;

    protected boolean proxyDisabled;

    protected AbstractNetwork(@NonNull final Random random, final boolean useRandomNodeIds) {
        this.random = requireNonNull(random);
        this.useRandomNodeIds = useRandomNodeIds;
        // Initialize with default GeoMeshTopology
        this.currentTopology = new GeoMeshTopologyImpl(
                GeoMeshTopologyConfiguration.DEFAULT, random, this::createNodes, this::createInstrumentedNode);
        this.networkConfiguration = new NetworkConfiguration();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Topology topology() {
        return currentTopology;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network topology(@NonNull final TopologyConfiguration configuration) {
        // Only allow topology configuration during network initialization
        throwIfNotInLifecycle(Lifecycle.INIT, "Topology can only be configured during network initialization.");

        requireNonNull(configuration);

        // Prevent reconfiguration if nodes already added
        if (!topology().nodes().isEmpty()) {
            throw new IllegalStateException("Cannot configure topology after nodes have been added to the network.");
        }

        // Dispatch to appropriate implementation based on configuration type
        this.currentTopology = switch (configuration) {
            case MeshTopologyConfiguration meshConfig ->
                new MeshTopologyImpl(meshConfig, this::createNodes, this::createInstrumentedNode);
            case GeoMeshTopologyConfiguration geoConfig ->
                new GeoMeshTopologyImpl(geoConfig, random, this::createNodes, this::createInstrumentedNode);
            default ->
                throw new IllegalArgumentException("Unknown topology configuration type: " + configuration.getClass());
        };

        return this;
    }

    /**
     * Returns the time manager for this network.
     *
     * @return the {@link TimeManager} instance
     */
    @NonNull
    protected abstract TimeManager timeManager();

    /**
     * The {@link TransactionGenerator} for this network.
     *
     * @return the {@link TransactionGenerator} instance
     */
    @NonNull
    protected abstract TransactionGenerator transactionGenerator();

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AsyncNetworkActions withTimeout(@NonNull final Duration timeout) {
        return new AsyncNetworkActionsImpl(timeout);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void weightGenerator(@NonNull final WeightGenerator weightGenerator) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Cannot set weight generator when the network is running.");
        this.weightGenerator = requireNonNull(weightGenerator);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void nodeWeight(final long weight) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Cannot set weight generator when the network is running.");
        if (weight <= 0) {
            throw new IllegalArgumentException("Weight must be positive");
        }
        networkConfiguration.weight(weight);
        nodes().forEach(n -> n.weight(weight));
    }

    @Override
    public @NonNull Roster roster() {
        if (lifecycle == Lifecycle.INIT) {
            throw new IllegalStateException("The roster is not available before the network is started.");
        }
        return roster;
    }

    /**
     * Creates a new node with the given ID and keys and certificates. This is a factory method that subclasses must
     * implement to create nodes specific to the environment.
     *
     * @param nodeId the ID of the node to create
     * @param keysAndCerts the keys and certificates for the node
     * @return the newly created node
     */
    protected abstract Node doCreateNode(@NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts);

    private List<Node> createNodes(final int count) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Cannot add nodes while the network is running.");
        throwIfInLifecycle(Lifecycle.SHUTDOWN, "Cannot add nodes after the network has been started.");

        try {
            final List<NodeId> nodeIds =
                    IntStream.range(0, count).mapToObj(i -> getNextNodeId()).toList();
            return CryptoStatic.generateKeysAndCerts(nodeIds).entrySet().stream()
                    .map(e -> doCreateNode(e.getKey(), e.getValue()))
                    .toList();
        } catch (final ExecutionException | InterruptedException | KeyStoreException e) {
            throw new RuntimeException("Exception while generating KeysAndCerts", e);
        }
    }

    /**
     * Creates a new instrumented node with the given ID and keys and certificates. This is a factory method that must
     * be implemented by subclasses to create instrumented nodes specific to the environment.
     *
     * @param nodeId the ID of the instrumented node to create
     * @param keysAndCerts the keys and certificates for the instrumented node
     * @return the newly created instrumented node
     */
    protected abstract InstrumentedNode doCreateInstrumentedNode(
            @NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts);

    private InstrumentedNode createInstrumentedNode() {
        throwIfInLifecycle(Lifecycle.RUNNING, "Cannot add nodes while the network is running.");
        throwIfInLifecycle(Lifecycle.SHUTDOWN, "Cannot add nodes after the network has been started.");

        try {
            final NodeId nodeId = getNextNodeId();
            final KeysAndCerts keysAndCerts =
                    CryptoStatic.generateKeysAndCerts(List.of(nodeId)).get(nodeId);
            return doCreateInstrumentedNode(nodeId, keysAndCerts);
        } catch (final ExecutionException | InterruptedException | KeyStoreException e) {
            throw new RuntimeException("Exception while generating KeysAndCerts", e);
        }
    }

    @NonNull
    private NodeId getNextNodeId() {
        final NodeId nextId = nextNodeId;
        // If enabled, advance by a random number of steps between 1 and 3
        final int randomAdvance = (useRandomNodeIds) ? random.nextInt(3) : 0;

        nextNodeId = nextNodeId.getOffset(randomAdvance + 1L);
        return nextId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void disableProxy() {
        proxyDisabled = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        doStart(DEFAULT_TIMEOUT);
    }

    /**
     * A hook method that is called before the network is started.
     *
     * <p>Subclasses can override this method to add custom behavior before the network starts, such as initializing
     * resources or performing setup tasks. They can also modify the roster if needed.
     *
     * @param roster the preliminary roster generated for the network
     */
    protected abstract void preStartHook(@NonNull final Roster roster);

    private void doStart(@NonNull final Duration timeout) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Network is already running.");
        log.info("Starting network...");

        roster = createRoster();
        preStartHook(roster);

        lifecycle = Lifecycle.RUNNING;
        updateConnections();
        for (final Node node : nodes()) {
            ((AbstractNode) node).roster(roster);
            node.start();
        }

        transactionGenerator().start();

        log.debug("Waiting for nodes to become active...");
        timeManager().waitForCondition(() -> allNodesInStatus(ACTIVE), timeout);
        log.info("Network started.");
    }

    private Roster createRoster() {
        final boolean anyNodeHasExplicitWeight = nodes().stream().anyMatch(node -> node.weight() > 0);
        final List<RosterEntry> rosterEntries;
        if (anyNodeHasExplicitWeight) {
            rosterEntries = nodes().stream()
                    .sorted(Comparator.comparing(Node::selfId))
                    .map(node -> createRosterEntry(node, node.weight() == UNSET_WEIGHT ? 0 : node.weight()))
                    .toList();
        } else {
            final int count = nodes().size();
            final Iterator<Long> weightIterator =
                    weightGenerator.getWeights(random.nextLong(), count).iterator();

            rosterEntries = nodes().stream()
                    .sorted(Comparator.comparing(Node::selfId))
                    .map(node -> createRosterEntry(node, weightIterator.next()))
                    .toList();
        }
        return Roster.newBuilder().rosterEntries(rosterEntries).build();
    }

    private RosterEntry createRosterEntry(final Node node, final long weight) {
        try {
            final long id = node.selfId().id();
            final byte[] certificate =
                    ((AbstractNode) node).gossipCaCertificate().getEncoded();
            return RosterEntry.newBuilder()
                    .nodeId(id)
                    .weight(weight)
                    .gossipCaCertificate(Bytes.wrap(certificate))
                    .gossipEndpoint(ServiceEndpoint.newBuilder()
                            .domainName(String.format(NODE_IDENTIFIER_FORMAT, id))
                            .port(GOSSIP_PORT)
                            .build())
                    .build();
        } catch (final CertificateEncodingException e) {
            throw new RuntimeException("Exception while creating roster entry", e);
        }
    }

    /**
     * The actual implementation of sending a quiescence command, to be provided by subclasses.
     *
     * @param command the quiescence command to send
     * @param timeout the maximum duration to wait for the command to be processed
     */
    protected abstract void doSendQuiescenceCommand(@NonNull QuiescenceCommand command, @NonNull Duration timeout);

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendQuiescenceCommand(@NonNull final QuiescenceCommand command) {
        doSendQuiescenceCommand(command, DEFAULT_TIMEOUT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public BidirectionalConnection bidirectionalConnection(@NonNull final Node node1, @NonNull final Node node2) {
        return new BidirectionalConnectionImpl(
                unidirectionalConnection(node1, node2), unidirectionalConnection(node2, node1));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public UnidirectionalConnection unidirectionalConnection(@NonNull final Node sender, @NonNull final Node receiver) {
        return new UnidirectionalConnectionImpl(sender, receiver);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ConnectionState connectionState(@NonNull final Node sender, @NonNull final Node receiver) {
        final ConnectionKey key = new ConnectionKey(sender.selfId(), receiver.selfId());
        ConnectionState connectionState = topology().getConnectionData(sender, receiver);
        if (getNetworkPartitionContaining(sender) != getNetworkPartitionContaining(receiver)) {
            connectionState = connectionState.withConnected(false);
        }
        final Boolean isConnected = connected.get(key);
        if (isConnected != null) {
            connectionState = connectionState.withConnected(isConnected);
        }
        final LatencyOverride latencyOverride = latencyOverrides.get(key);
        if (latencyOverride != null) {
            connectionState = connectionState.withLatencyAndJitter(latencyOverride.latency(), latencyOverride.jitter());
        }
        final BandwidthLimit bandwidthOverride = bandwidthOverrides.get(key);
        if (bandwidthOverride != null) {
            connectionState = connectionState.withBandwidthLimit(bandwidthOverride);
        }
        return connectionState;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Partition createNetworkPartition(@NonNull final Collection<Node> partitionNodes) {
        log.info("Creating network partition...");
        if (partitionNodes.isEmpty()) {
            throw new IllegalArgumentException("Cannot create a partition with no nodes.");
        }
        final PartitionImpl partition = new PartitionImpl(partitionNodes);
        final List<Node> allNodes = nodes();
        if (partition.size() == allNodes.size()) {
            throw new IllegalArgumentException("Cannot create a partition with all nodes.");
        }
        for (final Node node : partitionNodes) {
            final PartitionImpl oldPartition = networkPartitions.put(node.selfId(), partition);
            if (oldPartition != null) {
                oldPartition.nodes.remove(node);
            }
        }
        if (remainingNetworkPartition == null) {
            final List<Node> remainingNodes = allNodes.stream()
                    .filter(node -> !partitionNodes.contains(node))
                    .toList();
            remainingNetworkPartition = new PartitionImpl(remainingNodes);
            for (final Node node : remainingNodes) {
                networkPartitions.put(node.selfId(), remainingNetworkPartition);
            }
        }
        updateConnections();
        log.info("Network partition created.");
        return partition;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeNetworkPartition(@NonNull final Partition partition) {
        log.info("Removing network partition...");
        final Set<Partition> allPartitions = networkPartitions();
        if (!allPartitions.contains(partition)) {
            throw new IllegalArgumentException("Partition does not exist in the network: " + partition);
        }
        if (allPartitions.size() == 2) {
            // If only two partitions exist, clear all
            networkPartitions.clear();
            remainingNetworkPartition = null;
        } else {
            assert remainingNetworkPartition != null; // because there are at least 3 partitions
            for (final Node node : partition.nodes()) {
                networkPartitions.put(node.selfId(), remainingNetworkPartition);
                remainingNetworkPartition.nodes.add(node);
            }
        }
        updateConnections();
        log.info("Network partition removed.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<Partition> networkPartitions() {
        return Set.copyOf(networkPartitions.values());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public Partition getNetworkPartitionContaining(@NonNull final Node node) {
        return networkPartitions.get(node.selfId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Partition isolate(@NonNull final Node node) {
        return createNetworkPartition(Set.of(node));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void rejoin(@NonNull final Node node) {
        final Partition partition = networkPartitions.get(node.selfId());
        if (partition == null) {
            throw new IllegalArgumentException("Node is not isolated: " + node.selfId());
        }
        removeNetworkPartition(partition);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isIsolated(@NonNull final Node node) {
        final Partition partition = networkPartitions.get(node.selfId());
        return partition != null && partition.size() == 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLatencyForAllConnections(@NonNull final Node node, @NonNull final LatencyRange latencyRange) {
        log.info("Setting latency for all connections from node {} to range {}", node.selfId(), latencyRange);
        for (final Node otherNode : nodes()) {
            if (!node.equals(otherNode)) {
                setLatencyRange(node, otherNode, latencyRange);
                setLatencyRange(otherNode, node, latencyRange);
            }
        }
        updateConnections();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void restoreLatencyForAllConnections(@NonNull final Node node) {
        log.info("Restoring latency for all connections from node {}", node);
        for (final Node otherNode : nodes()) {
            if (!node.equals(otherNode)) {
                latencyOverrides.remove(new ConnectionKey(node.selfId(), otherNode.selfId()));
                latencyOverrides.remove(new ConnectionKey(otherNode.selfId(), node.selfId()));
            }
        }
        updateConnections();
    }

    private void setLatencyRange(
            @NonNull final Node sender, @NonNull final Node receiver, @NonNull final LatencyRange latencyRange) {
        final long nanos = latencyRange.min().equals(latencyRange.max())
                ? latencyRange.max().toNanos()
                : random.nextLong(
                        latencyRange.min().toNanos(), latencyRange.max().toNanos());
        final LatencyOverride latencyOverride =
                new LatencyOverride(Duration.ofNanos(nanos), latencyRange.jitterPercent());
        latencyOverrides.put(new ConnectionKey(sender.selfId(), receiver.selfId()), latencyOverride);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBandwidthForAllConnections(@NonNull final Node node, @NonNull final BandwidthLimit bandwidthLimit) {
        log.info("Setting bandwidth for all connections from node {} to {}", node.selfId(), bandwidthLimit);
        for (final Node otherNode : nodes()) {
            if (!node.equals(otherNode)) {
                bandwidthOverrides.put(new ConnectionKey(node.selfId(), otherNode.selfId()), bandwidthLimit);
                bandwidthOverrides.put(new ConnectionKey(otherNode.selfId(), node.selfId()), bandwidthLimit);
            }
        }
        updateConnections();
    }

    @Override
    public void restoreBandwidthLimitsForAllConnections(@NonNull final Node node) {
        log.info("Restoring bandwidth for all connections from node {}", node);
        for (final Node otherNode : nodes()) {
            if (!node.equals(otherNode)) {
                bandwidthOverrides.remove(new ConnectionKey(node.selfId(), otherNode.selfId()));
                bandwidthOverrides.remove(new ConnectionKey(otherNode.selfId(), node.selfId()));
            }
        }
        updateConnections();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void restoreConnectivity() {
        networkPartitions.clear();
        connected.clear();
        latencyOverrides.clear();
        bandwidthOverrides.clear();
        updateConnections();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void freeze() {
        doFreeze(DEFAULT_TIMEOUT);
    }

    private void doFreeze(@NonNull final Duration timeout) {
        throwIfInLifecycle(Lifecycle.INIT, "Network has not been started yet.");
        throwIfInLifecycle(Lifecycle.SHUTDOWN, "Network has been shut down.");

        log.info("Sending freeze transaction...");
        final OtterTransaction freezeTransaction = TransactionFactory.createFreezeTransaction(
                random.nextLong(), timeManager().now().plus(FREEZE_DELAY));
        submitTransaction(freezeTransaction);

        log.debug("Waiting for nodes to freeze...");
        timeManager()
                .waitForCondition(
                        () -> allNodesInStatus(FREEZE_COMPLETE),
                        timeout,
                        "Timeout while waiting for all nodes to freeze.");

        transactionGenerator().stop();

        log.info("Network frozen.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void triggerCatastrophicIss() {
        doTriggerCatastrophicIss(DEFAULT_TIMEOUT);
    }

    private void doTriggerCatastrophicIss(@NonNull final Duration defaultTimeout) {
        throwIfNotInLifecycle(Lifecycle.RUNNING, "Network must be running to trigger an ISS.");

        log.info("Sending Catastrophic ISS triggering transaction...");
        final Instant start = timeManager().now();
        final OtterTransaction issTransaction = TransactionFactory.createIssTransaction(random.nextLong(), nodes());
        submitTransaction(issTransaction);
        final Duration elapsed = Duration.between(start, timeManager().now());

        log.debug("Waiting for Catastrophic ISS to trigger...");

        // Depending on the test configuration, some nodes may enter CHECKING when a catastrophic ISS occurs,
        // but at least one node should always enter CATASTROPHIC_FAILURE.
        timeManager()
                .waitForCondition(
                        this::allNodesInCheckingOrCatastrophicFailure,
                        defaultTimeout.minus(elapsed),
                        "Not all nodes entered CHECKING or CATASTROPHIC_FAILURE before timeout");
        final long numInCatastrophicFailure = nodes().stream()
                .filter(node -> node.platformStatus() == CATASTROPHIC_FAILURE)
                .count();
        if (numInCatastrophicFailure < 1) {
            fail("No node entered CATASTROPHIC_FAILURE");
        }
    }

    private boolean allNodesInCheckingOrCatastrophicFailure() {
        return nodes().stream().allMatch(node -> {
            final PlatformStatus status = node.platformStatus();
            return status == CATASTROPHIC_FAILURE || status == CHECKING;
        });
    }

    /**
     * {@inheritDoc}
     */
    public void submitTransactions(@NonNull final List<OtterTransaction> transactions) {
        nodes().stream()
                .filter(Node::isActive)
                .findFirst()
                .map(node -> (AbstractNode) node)
                .orElseThrow(() -> new AssertionError("No active node found to send transaction to."))
                .submitTransactions(transactions);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, @NonNull final String value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Network withConfigValue(@NonNull final String key, @NonNull final Duration value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final int value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final long value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, @NonNull final Path value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final boolean value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final double value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, @NonNull final Enum<?> value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, @NonNull final List<String> values) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, values);
        nodes().forEach(node -> node.configuration().withConfigValue(key, values));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, @NonNull final TaskSchedulerConfiguration value) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Configuration modification is not allowed when the network is running.");
        networkConfiguration.withConfigValue(key, value);
        nodes().forEach(node -> node.configuration().withConfigValue(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shutdown() {
        doShutdown(DEFAULT_TIMEOUT);
    }

    private void doShutdown(@NonNull final Duration timeout) {
        throwIfInLifecycle(Lifecycle.INIT, "Network has not been started yet.");
        throwIfInLifecycle(Lifecycle.SHUTDOWN, "Network has already been shut down.");

        log.info("Killing nodes immediately...");
        for (final Node node : nodes()) {
            node.killImmediately();
        }

        lifecycle = Lifecycle.SHUTDOWN;

        transactionGenerator().stop();

        log.info("Nodes have been killed.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void version(@NonNull final SemanticVersion version) {
        throwIfInLifecycle(Lifecycle.RUNNING, "Cannot set version while the network is running");
        networkConfiguration.version(version);
        nodes().forEach(node -> node.version(version));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bumpConfigVersion() {
        nodes().forEach(Node::bumpConfigVersion);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodeConsensusResults newConsensusResults() {
        final List<SingleNodeConsensusResult> results =
                nodes().stream().map(Node::newConsensusResult).toList();
        return new MultipleNodeConsensusResultsImpl(results);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MultipleNodeLogResults newLogResults() {
        final List<SingleNodeLogResult> results =
                nodes().stream().map(Node::newLogResult).toList();

        return new MultipleNodeLogResultsImpl(results);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodePlatformStatusResults newPlatformStatusResults() {
        final List<SingleNodePlatformStatusResult> statusProgressions =
                nodes().stream().map(Node::newPlatformStatusResult).toList();
        return new MultipleNodePlatformStatusResultsImpl(statusProgressions);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodeReconnectResults newReconnectResults() {
        final List<SingleNodeReconnectResult> reconnectResults =
                nodes().stream().map(Node::newReconnectResult).toList();
        return new MultipleNodeReconnectResultsImpl(reconnectResults);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodePcesResults newPcesResults() {
        final List<SingleNodePcesResult> results =
                nodes().stream().map(Node::newPcesResult).toList();
        return new MultipleNodePcesResultsImpl(results);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodeEventStreamResults newEventStreamResults() {
        return new MultipleNodeEventStreamResultsImpl(nodes());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean nodeIsBehindByNodeWeight(@NonNull final Node maybeBehindNode) {
        final Set<Node> otherNodes = nodes().stream()
                .filter(n -> !n.selfId().equals(maybeBehindNode.selfId()))
                .collect(Collectors.toSet());

        // For simplicity, consider the node that we are checking as "behind" to be the "self" node.
        final EventWindow selfEventWindow = maybeBehindNode.newConsensusResult().getLatestEventWindow();

        long weightOfAheadNodes = 0;
        for (final Node maybeAheadNode : otherNodes) {
            final EventWindow peerEventWindow =
                    maybeAheadNode.newConsensusResult().getLatestEventWindow();

            // If any peer in the required list says the "self" node is not behind, the node is not behind.
            if (FallenBehindStatus.getStatus(selfEventWindow, peerEventWindow)
                    != FallenBehindStatus.SELF_FALLEN_BEHIND) {
                weightOfAheadNodes += maybeAheadNode.weight();
            }
        }
        return Threshold.STRONG_MINORITY.isSatisfiedBy(weightOfAheadNodes, totalWeight());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean nodesAreBehindByNodeCount(
            @NonNull final Node maybeBehindNode, @Nullable final Node... otherMaybeBehindNodes) {
        final Set<Node> maybeBehindNodes = Utils.toSet(maybeBehindNode, otherMaybeBehindNodes);
        final Set<Node> peerNodes =
                nodes().stream().filter(n -> !maybeBehindNodes.contains(n)).collect(Collectors.toSet());

        boolean allNodesAreBehind = true;
        for (final Node node : maybeBehindNodes) {
            // For simplicity, consider the node that we are checking as "behind" to be the "self" node.
            final EventWindow selfEventWindow = node.newConsensusResult().getLatestEventWindow();

            int numNodesAhead = 0;
            for (final Node maybeAheadNode : peerNodes) {
                final EventWindow peerEventWindow =
                        maybeAheadNode.newConsensusResult().getLatestEventWindow();
                final EventWindow peerEventWindowWithBuffer = new EventWindow(
                        peerEventWindow.latestConsensusRound(),
                        peerEventWindow.newEventBirthRound(),
                        peerEventWindow.ancientThreshold(),
                        Math.max(
                                ConsensusConstants.ROUND_FIRST,
                                peerEventWindow.expiredThreshold()
                                        - 5)); // add buffer to account for unpropagated event windows

                // If any peer in the required list says the "self" node is behind, it is ahead so add it to the count
                if (FallenBehindStatus.getStatus(selfEventWindow, peerEventWindowWithBuffer)
                        == FallenBehindStatus.SELF_FALLEN_BEHIND) {
                    numNodesAhead++;
                }
            }
            allNodesAreBehind &= (numNodesAhead / (1.0 * peerNodes.size())) >= BEHIND_FRACTION;
        }
        return allNodesAreBehind;
    }

    @Override
    public void savedStateDirectory(@NonNull final Path savedStateDirectory) {
        final Path resolvedPath = OtterSavedStateUtils.findSaveState(requireNonNull(savedStateDirectory));
        networkConfiguration.savedStateDirectory(resolvedPath);
        nodes().forEach(node -> node.startFromSavedState(resolvedPath));
    }

    /**
     * Throws an {@link IllegalStateException} if the network is in the given state.
     *
     * @param expected the state that will cause the exception to be thrown
     * @param message the message to include in the exception
     * @throws IllegalStateException if the network is in the expected state
     */
    protected void throwIfInLifecycle(@NonNull final Lifecycle expected, @NonNull final String message) {
        if (lifecycle == expected) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Throws an {@link IllegalStateException} if the network is not in the given state.
     *
     * @param desiredLifecycle the state that will NOT cause the exception to be thrown
     * @param message the message to include in the exception
     * @throws IllegalStateException if the network is not in the expected state
     */
    protected void throwIfNotInLifecycle(@NonNull final Lifecycle desiredLifecycle, @NonNull final String message) {
        if (lifecycle != desiredLifecycle) {
            throw new IllegalStateException(message);
        }
    }

    private void updateConnections() {
        final Map<ConnectionKey, ConnectionState> connections = new HashMap<>();
        for (final Node sender : nodes()) {
            for (final Node receiver : nodes()) {
                if (sender.selfId().equals(receiver.selfId())) {
                    continue; // Skip self-connections
                }
                final ConnectionKey key = new ConnectionKey(sender.selfId(), receiver.selfId());
                final ConnectionState connectionState = connectionState(sender, receiver);
                connections.put(key, connectionState);
            }
        }
        onConnectionsChanged(connections);
    }

    /**
     * Callback method to handle changes in the network connections.
     *
     * <p>This method is called whenever the connections in the network change, such as when partitions are created or
     * removed. This allows subclasses to react to changes in the network topology.
     *
     * @param connections a map of connections representing the current state of the network
     */
    protected abstract void onConnectionsChanged(@NonNull final Map<ConnectionKey, ConnectionState> connections);

    /**
     * Default implementation of {@link AsyncNetworkActions}
     */
    protected class AsyncNetworkActionsImpl implements AsyncNetworkActions {

        private final Duration timeout;

        /**
         * Constructs an instance of {@link AsyncNetworkActionsImpl} with the specified timeout.
         *
         * @param timeout the duration to wait for actions to complete
         */
        public AsyncNetworkActionsImpl(@NonNull final Duration timeout) {
            this.timeout = timeout;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void start() {
            doStart(timeout);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void freeze() {
            doFreeze(timeout);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void shutdown() {
            doShutdown(timeout);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void triggerCatastrophicIss() {
            doTriggerCatastrophicIss(timeout);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void sendQuiescenceCommand(@NonNull final QuiescenceCommand command) {
            doSendQuiescenceCommand(command, timeout);
        }
    }

    private static class PartitionImpl implements Partition {

        private final Set<Node> nodes = new HashSet<>();

        /**
         * Creates a partition from a collection of nodes.
         *
         * @param nodes the nodes to include in the partition
         */
        public PartitionImpl(@NonNull final Collection<? extends Node> nodes) {
            this.nodes.addAll(nodes);
        }

        /**
         * Gets the nodes in this partition.
         *
         * <p>Note: While the returned set is unmodifiable, the {@link Set} can still change if the partitions are
         * changed
         *
         * @return an unmodifiable set of nodes in this partition
         */
        @NonNull
        public Set<Node> nodes() {
            return Collections.unmodifiableSet(nodes);
        }

        /**
         * Checks if the partition contains the specified node.
         *
         * @param node the node to check
         * @return true if the node is in this partition
         */
        public boolean contains(@NonNull final Node node) {
            return nodes.contains(requireNonNull(node));
        }

        /**
         * Gets the number of nodes in this partition.
         *
         * @return the size of the partition
         */
        public int size() {
            return nodes.size();
        }
    }

    /**
     * Implementation of the UnidirectionalConnection interface.
     */
    private class UnidirectionalConnectionImpl implements UnidirectionalConnection {

        private final Node sender;
        private final Node receiver;
        private final ConnectionKey connectionKey;

        /**
         * Constructs a UnidirectionalConnectionImpl with the specified start and end nodes and a supplier for base connection data.
         *
         * @param sender         the starting node of the connection
         * @param receiver           the ending node of the connection
         * @throws NullPointerException if any of the parameters are null
         */
        public UnidirectionalConnectionImpl(@NonNull final Node sender, @NonNull final Node receiver) {
            this.sender = requireNonNull(sender);
            this.receiver = requireNonNull(receiver);
            this.connectionKey = new ConnectionKey(sender.selfId(), receiver.selfId());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public Node sender() {
            return sender;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public Node receiver() {
            return receiver;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void disconnect() {
            log.info("Disconnecting connection from node {} to node {}", sender.selfId(), receiver.selfId());
            connected.put(connectionKey, false);
            updateConnections();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void connect() {
            log.info("Connecting connection from node {} to node {}", sender.selfId(), receiver.selfId());
            connected.put(connectionKey, true);
            updateConnections();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isConnected() {
            return connectionState(sender, receiver).connected();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void restoreConnectivity() {
            log.info("Restoring connectivity from node {} to node {}", sender.selfId(), receiver.selfId());
            connected.remove(connectionKey);
            restoreLatency();
            restoreBandwidthLimit();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public Duration latency() {
            return connectionState(sender, receiver).latency();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void latency(@NonNull final Duration latency) {
            requireNonNull(latency);
            log.info("Setting latency from node {} to node {} to {}", sender.selfId(), receiver.selfId(), latency);
            latencyOverrides.put(connectionKey, new LatencyOverride(latency, jitter()));
            updateConnections();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public Percentage jitter() {
            return connectionState(sender, receiver).jitter();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void jitter(@NonNull final Percentage jitter) {
            requireNonNull(jitter);
            log.info("Setting jitter from node {} to node {} to {}", sender.selfId(), receiver.selfId(), jitter);
            latencyOverrides.put(connectionKey, new LatencyOverride(latency(), jitter));
            updateConnections();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void restoreLatency() {
            log.info("Restoring latency from node {} to node {}", sender.selfId(), receiver.selfId());
            latencyOverrides.remove(connectionKey);
            updateConnections();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public BandwidthLimit bandwidthLimit() {
            return connectionState(sender, receiver).bandwidthLimit();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void bandwidthLimit(@NonNull final BandwidthLimit bandwidthLimit) {
            requireNonNull(bandwidthLimit);
            log.info(
                    "Setting bandwidth limit from node {} to node {} to {}",
                    sender.selfId(),
                    receiver.selfId(),
                    bandwidthLimit);
            bandwidthOverrides.put(connectionKey, bandwidthLimit);
            updateConnections();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void restoreBandwidthLimit() {
            log.info("Restoring bandwidth limit from node {} to node {}", sender.selfId(), receiver.selfId());
            bandwidthOverrides.remove(connectionKey);
            updateConnections();
        }
    }

    private record LatencyOverride(
            @NonNull Duration latency, @NonNull Percentage jitter) {}
}
