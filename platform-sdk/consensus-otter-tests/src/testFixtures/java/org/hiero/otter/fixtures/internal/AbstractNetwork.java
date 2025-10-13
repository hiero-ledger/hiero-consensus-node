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
import com.swirlds.common.test.fixtures.WeightGenerator;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.utility.Threshold;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.gossip.shadowgraph.SyncFallenBehindStatus;
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
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.AsyncNetworkActions;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionFactory;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.app.OtterTransaction;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.internal.network.GeoMeshTopologyImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodeConsensusResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodeLogResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodeMarkerFileResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodePcesResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodePlatformStatusResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodeReconnectResultsImpl;
import org.hiero.otter.fixtures.network.Partition;
import org.hiero.otter.fixtures.network.Topology;
import org.hiero.otter.fixtures.network.Topology.ConnectionData;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.MultipleNodeMarkerFileResults;
import org.hiero.otter.fixtures.result.MultipleNodePcesResults;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.MultipleNodeReconnectResults;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodeMarkerFileResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;

/**
 * An abstract base class for a network implementation that provides common functionality shared by the different
 * environments.
 */
public abstract class AbstractNetwork implements Network {
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
    private final Topology topology;

    protected Lifecycle lifecycle = Lifecycle.INIT;
    protected WeightGenerator weightGenerator = WeightGenerators.GAUSSIAN;

    @Nullable
    private PartitionImpl remainingNetworkPartition;

    private NodeId nextNodeId = NodeId.FIRST_NODE_ID;

    protected AbstractNetwork(@NonNull final Random random) {
        this.random = requireNonNull(random);
        this.topology = new GeoMeshTopologyImpl(random, this::createNodes, this::createInstrumentedNode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Topology topology() {
        return topology;
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
        if (weight <= 0) {
            throw new IllegalArgumentException("Weight must be positive");
        }
        if (nodes().isEmpty()) {
            throw new IllegalStateException("Cannot set node weight when there are no nodes in the network.");
        }
        nodes().forEach(n -> n.weight(weight));
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
            return CryptoStatic.generateKeysAndCerts(nodeIds, null).entrySet().stream()
                    .map(entry -> doCreateNode(entry.getKey(), entry.getValue()))
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
                    CryptoStatic.generateKeysAndCerts(List.of(nodeId), null).get(nodeId);
            return doCreateInstrumentedNode(nodeId, keysAndCerts);
        } catch (final ExecutionException | InterruptedException | KeyStoreException e) {
            throw new RuntimeException("Exception while generating KeysAndCerts", e);
        }
    }

    @NonNull
    private NodeId getNextNodeId() {
        final NodeId nextId = nextNodeId;
        // randomly advance between 1 and 3 steps
        final int randomAdvance = random.nextInt(3);
        nextNodeId = nextNodeId.getOffset(randomAdvance + 1L);
        return nextId;
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

        final Roster roster = createRoster();
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
    public Partition createNetworkPartition(@NonNull final Collection<Node> partitionNodes) {
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
        return partition;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removePartition(@NonNull final Partition partition) {
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
        removePartition(partition);
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
    public void restoreConnectivity() {
        networkPartitions.clear();
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
     * Submits the transaction to the first active node found in the network.
     *
     * @param transaction the transaction to submit
     */
    private void submitTransaction(@NonNull final OtterTransaction transaction) {
        nodes().stream()
                .filter(Node::isActive)
                .findFirst()
                .map(node -> (AbstractNode) node)
                .orElseThrow(() -> new AssertionError("No active node found to send transaction to."))
                .submitTransaction(transaction);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, @NonNull final String value) {
        requireNodesBeforeConfigChange();
        nodes().forEach(node -> node.configuration().set(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final int value) {
        requireNodesBeforeConfigChange();
        nodes().forEach(node -> node.configuration().set(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final long value) {
        requireNodesBeforeConfigChange();
        nodes().forEach(node -> node.configuration().set(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, @NonNull final Path value) {
        requireNodesBeforeConfigChange();
        nodes().forEach(node -> node.configuration().set(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final boolean value) {
        requireNodesBeforeConfigChange();
        nodes().forEach(node -> node.configuration().set(key, value));
        return this;
    }

    private void requireNodesBeforeConfigChange() {
        if (nodes().isEmpty()) {
            throw new IllegalStateException("Cannot update configuration without nodes in the network.");
        }
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
    public MultipleNodeMarkerFileResults newMarkerFileResults() {
        final List<SingleNodeMarkerFileResult> results =
                nodes().stream().map(Node::newMarkerFileResult).toList();
        return new MultipleNodeMarkerFileResultsImpl(results);
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
            if (SyncFallenBehindStatus.getStatus(selfEventWindow, peerEventWindow)
                    != SyncFallenBehindStatus.SELF_FALLEN_BEHIND) {
                weightOfAheadNodes += maybeAheadNode.weight();
            }
        }
        return Threshold.STRONG_MINORITY.isSatisfiedBy(weightOfAheadNodes, totalWeight());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean nodeIsBehindByNodeCount(@NonNull final Node maybeBehindNode, final double fraction) {
        final Set<Node> otherNodes = nodes().stream()
                .filter(n -> !n.selfId().equals(maybeBehindNode.selfId()))
                .collect(Collectors.toSet());

        // For simplicity, consider the node that we are checking as "behind" to be the "self" node.
        final EventWindow selfEventWindow = maybeBehindNode.newConsensusResult().getLatestEventWindow();

        int numNodesAhead = 0;
        for (final Node maybeAheadNode : otherNodes) {
            final EventWindow peerEventWindow =
                    maybeAheadNode.newConsensusResult().getLatestEventWindow();

            // If any peer in the required list says the "self" node is behind, it is ahead so add it to the count
            if (SyncFallenBehindStatus.getStatus(selfEventWindow, peerEventWindow)
                    == SyncFallenBehindStatus.SELF_FALLEN_BEHIND) {
                numNodesAhead++;
            }
        }
        return (numNodesAhead / (1.0 * otherNodes.size())) >= fraction;
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
        final Map<ConnectionKey, ConnectionData> connections = new HashMap<>();
        for (final Node sender : nodes()) {
            for (final Node receiver : nodes()) {
                if (sender.selfId().equals(receiver.selfId())) {
                    continue; // Skip self-connections
                }
                final ConnectionKey key = new ConnectionKey(sender.selfId(), receiver.selfId());
                ConnectionData connectionData = topology().getConnectionData(sender, receiver);
                if (getNetworkPartitionContaining(sender) != getNetworkPartitionContaining(receiver)) {
                    connectionData = connectionData.withConnected(false);
                }
                // add other effects (e.g., clique, latency) on connections here
                connections.put(key, connectionData);
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
    protected abstract void onConnectionsChanged(@NonNull final Map<ConnectionKey, ConnectionData> connections);

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
}
