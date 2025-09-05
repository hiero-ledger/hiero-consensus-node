// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.fail;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZE_COMPLETE;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.common.test.fixtures.WeightGenerator;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.utility.Threshold;
import com.swirlds.platform.gossip.shadowgraph.SyncFallenBehindStatus;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.otter.fixtures.AsyncNetworkActions;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionFactory;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.internal.result.MultipleNodeConsensusResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodeLogResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodeMarkerFileResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodePcesResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodePlatformStatusResultsImpl;
import org.hiero.otter.fixtures.internal.result.MultipleNodeReconnectResultsImpl;
import org.hiero.otter.fixtures.network.Partition;
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

    private static final Logger log = LogManager.getLogger();

    private static final Duration FREEZE_DELAY = Duration.ofSeconds(10L);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2L);

    /**
     * The state of the network.
     */
    protected enum State {
        INIT,
        RUNNING,
        SHUTDOWN
    }

    protected State state = State.INIT;

    protected WeightGenerator weightGenerator = WeightGenerators.GAUSSIAN;

    private final Map<NodeId, PartitionImpl> partitions = new HashMap<>();

    @Nullable
    private PartitionImpl remainingPartition;

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
    public void setWeightGenerator(@NonNull final WeightGenerator weightGenerator) {
        if (!nodes().isEmpty()) {
            throw new IllegalStateException("Cannot set weight generator after nodes have been added to the network.");
        }
        this.weightGenerator = requireNonNull(weightGenerator);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        doStart(DEFAULT_TIMEOUT);
    }

    private void doStart(@NonNull final Duration timeout) {
        throwIfInState(State.RUNNING, "Network is already running.");

        log.info("Starting network...");
        state = State.RUNNING;
        updateConnections();
        for (final Node node : nodes()) {
            node.start();
        }

        transactionGenerator().start();

        log.debug("Waiting for nodes to become active...");
        if (!timeManager().waitForCondition(() -> allNodesInStatus(ACTIVE), timeout)) {
            fail("Timeout while waiting for nodes to become active.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Partition createPartition(@NonNull final Collection<Node> partitionNodes) {
        if (partitionNodes.isEmpty()) {
            throw new IllegalArgumentException("Cannot create a partition with no nodes.");
        }
        final PartitionImpl partition = new PartitionImpl(partitionNodes);
        final List<Node> allNodes = nodes();
        if (partition.size() == allNodes.size()) {
            throw new IllegalArgumentException("Cannot create a partition with all nodes.");
        }
        for (final Node node : partitionNodes) {
            final PartitionImpl oldPartition = partitions.put(node.selfId(), partition);
            if (oldPartition != null) {
                oldPartition.nodes.remove(node);
            }
        }
        if (remainingPartition == null) {
            final List<Node> remainingNodes = allNodes.stream()
                    .filter(node -> !partitionNodes.contains(node))
                    .toList();
            remainingPartition = new PartitionImpl(remainingNodes);
            for (final Node node : remainingNodes) {
                partitions.put(node.selfId(), remainingPartition);
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
        final Set<Partition> allPartitions = partitions();
        if (!allPartitions.contains(partition)) {
            throw new IllegalArgumentException("Partition does not exist in the network: " + partition);
        }
        if (allPartitions.size() == 2) {
            // If only two partitions exist, clear all
            partitions.clear();
            remainingPartition = null;
        } else {
            assert remainingPartition != null; // because there are at least 3 partitions
            for (final Node node : partition.nodes()) {
                partitions.put(node.selfId(), remainingPartition);
                remainingPartition.nodes.add(node);
            }
        }
        updateConnections();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<Partition> partitions() {
        return Set.copyOf(partitions.values());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public Partition getPartitionContaining(@NonNull final Node node) {
        return partitions.get(node.selfId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Partition isolate(@NonNull final Node node) {
        return createPartition(Set.of(node));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void rejoin(@NonNull final Node node) {
        final Partition partition = partitions.get(node.selfId());
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
        final Partition partition = partitions.get(node.selfId());
        return partition != null && partition.size() == 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void restoreConnectivity() {
        partitions.clear();
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
        throwIfInState(State.INIT, "Network has not been started yet.");
        throwIfInState(State.SHUTDOWN, "Network has been shut down.");

        log.info("Sending freeze transaction...");
        final byte[] freezeTransaction = TransactionFactory.createFreezeTransaction(
                        timeManager().now().plus(FREEZE_DELAY))
                .toByteArray();
        nodes().stream()
                .filter(Node::isActive)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No active node found to send freeze transaction to."))
                .submitTransaction(freezeTransaction);

        log.debug("Waiting for nodes to freeze...");
        if (!timeManager().waitForCondition(() -> allNodesInStatus(FREEZE_COMPLETE), timeout)) {
            fail("Timeout while waiting for all nodes to freeze.");
        }

        transactionGenerator().stop();
    }

    /**
     * {@inheritDoc}
     */
    public long totalWeight() {
        return nodes().stream().mapToLong(Node::weight).sum();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, @NonNull final String value) {
        nodes().forEach(node -> node.configuration().set(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final int value) {
        nodes().forEach(node -> node.configuration().set(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final long value) {
        nodes().forEach(node -> node.configuration().set(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, @NonNull final Path value) {
        nodes().forEach(node -> node.configuration().set(key, value));
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Network withConfigValue(@NonNull final String key, final boolean value) {
        nodes().forEach(node -> node.configuration().set(key, value));
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
        throwIfInState(State.INIT, "Network has not been started yet.");
        throwIfInState(State.SHUTDOWN, "Network has already been shut down.");

        log.info("Killing nodes immediately...");
        for (final Node node : nodes()) {
            node.killImmediately();
        }

        state = State.SHUTDOWN;

        transactionGenerator().stop();
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
    protected void throwIfInState(@NonNull final State expected, @NonNull final String message) {
        if (state == expected) {
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
                if (getPartitionContaining(sender) != getPartitionContaining(receiver)) {
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
         * <p>Note: While the returned set is unmodifiable, the {@link Set} can still change if the partitions are changed
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
