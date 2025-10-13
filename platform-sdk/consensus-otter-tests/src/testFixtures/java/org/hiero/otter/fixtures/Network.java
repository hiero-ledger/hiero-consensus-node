// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.common.test.fixtures.WeightGenerator;
import com.swirlds.common.test.fixtures.WeightGenerators;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.internal.helpers.Utils;
import org.hiero.otter.fixtures.network.Partition;
import org.hiero.otter.fixtures.network.Topology;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.MultipleNodeMarkerFileResults;
import org.hiero.otter.fixtures.result.MultipleNodePcesResults;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.MultipleNodeReconnectResults;

/**
 * Interface representing a network of nodes.
 *
 * <p>This interface provides methods to add and remove nodes, start the network, and add instrumented nodes.
 */
@SuppressWarnings("unused")
public interface Network {

    /**
     * Get the list of nodes in the network.
     *
     * <p>The {@link List} cannot be modified directly. However, if a node is added or removed from the network, the
     * list is automatically updated. That means, if it is necessary to have a constant list, it is recommended to
     * create a copy.
     *
     * @return a list of nodes in the network
     */
    @NonNull
    default List<Node> nodes() {
        return topology().nodes();
    }

    /**
     * Returns the {@link Topology} of the network.
     *
     * @return the topology of the network
     */
    @NonNull
    Topology topology();

    /**
     * Adds a single node to the network.
     *
     * <p>How the node is connected to existing nodes and its latency, jitter, and bandwidth depend on the current
     * topology.
     *
     * @return the created node
     */
    @NonNull
    default Node addNode() {
        return topology().addNode();
    }

    /**
     * Adds multiple nodes to the network.
     *
     * <p>How the node is connected to existing nodes and its latency, jitter, and bandwidth depend on the current
     * topology.
     *
     * @param count the number of nodes to add
     * @return list of created nodes
     */
    @NonNull
    default List<Node> addNodes(final int count) {
        return topology().addNodes(count);
    }

    /**
     * Add an instrumented node to the network.
     *
     * <p>How the node is connected to existing nodes and its latency, jitter, and bandwidth depend on the current
     * topology.
     *
     * <p>This method is used to add a node that has additional instrumentation for testing purposes.
     * For example, it can exhibit malicious or erroneous behavior.
     *
     * @return the added instrumented node
     */
    @NonNull
    default InstrumentedNode addInstrumentedNode() {
        return topology().addInstrumentedNode();
    }

    /**
     * Sets the weight generator for the network. The weight generator is used to assign weights to nodes if no nodes in
     * the network have their weight set explicitly via {@link Node#weight(long)}.
     *
     * <p>If no weight generator is set, the default {@link WeightGenerators#GAUSSIAN} is used.
     *
     * <p>Note that the weight generator can only be set before the network is started.
     *
     * @param weightGenerator the weight generator to use
     * @throws IllegalStateException if nodes have already been added to the network
     */
    void weightGenerator(@NonNull WeightGenerator weightGenerator);

    /**
     * Sets the weight of each node in the network to the specified value. Calling this method results in balanced
     * weight distribution.
     *
     * @param weight the weight to assign to each node. Must be positive.
     */
    void nodeWeight(long weight);

    /**
     * Gets the total weight of the network. Always positive.
     *
     * @return the network weight
     */
    default long totalWeight() {
        return nodes().stream().mapToLong(Node::weight).sum();
    }

    /**
     * Start the network with the currently configured setup.
     *
     * <p>The method will wait until all nodes have become
     * {@link org.hiero.consensus.model.status.PlatformStatus#ACTIVE}. It will wait for a environment-specific timeout
     * before throwing an exception if the nodes do not reach the {@code ACTIVE} state. The default can be overridden by
     * calling {@link #withTimeout(Duration)}.
     */
    void start();

    /**
     * Sets the quiescence command of the network.
     *
     * <p>The default command is {@link QuiescenceCommand#DONT_QUIESCE}.
     *
     * @param command the new quiescence command
     */
    void sendQuiescenceCommand(@NonNull QuiescenceCommand command);

    /**
     * Creates a network partition containing the specified nodes. Nodes within the partition remain connected to each
     * other, but are disconnected from all nodes outside the partition.
     *
     * <p>If a node is already in a partition, it will be removed from the old partition before being added to the new
     * one.
     *
     * <p>If there was no partition before, a second partition is created implicitly that contains the remaining nodes.
     *
     * @param nodes the nodes to include in the partition
     * @return the created Partition object
     * @throws IllegalArgumentException if {@code nodes} is empty or contains all nodes in the network
     */
    @NonNull
    Partition createNetworkPartition(@NonNull Collection<Node> nodes);

    /**
     * Creates a network partition containing the specified nodes. Nodes within the partition remain connected to each
     * other, but are disconnected from all nodes outside the partition.
     *
     * <p>If a node is already in a partition, it will be removed from the old partition before being added to the new
     * one.
     *
     * @param node0 the first node to include in the partition (mandatory)
     * @param nodes additional nodes to include in the partition (optional)
     * @return the created Partition object
     * @throws IllegalArgumentException if {@code nodes} is empty or contains all nodes in the network
     */
    @NonNull
    default Partition createNetworkPartition(@NonNull final Node node0, @NonNull final Node... nodes) {
        return createNetworkPartition(Utils.collect(node0, nodes));
    }

    /**
     * Removes a partition and restores connectivity for its nodes. Only restores changes made by creating the
     * partition.
     *
     * @param partition the partition to remove
     */
    void removePartition(@NonNull Partition partition);

    /**
     * Gets all currently active partitions.
     *
     * @return set of all active partitions
     */
    @NonNull
    Set<Partition> networkPartitions();

    /**
     * Gets the partition containing the specified node.
     *
     * @param node the node to search for
     * @return the partition containing the node, or {@code null} if not in any partition
     */
    @Nullable
    Partition getNetworkPartitionContaining(@NonNull Node node);

    /**
     * Isolates a node from the network. Disconnects all connections to and from this node.
     *
     * <p>This is equivalent to creating a partition with a single node. Consequently, a node that is part of a
     * partition will be removed from the old partition before being isolated.
     *
     * @param node the node to isolate
     * @return the created partition containing only the isolated node
     * @throws IllegalArgumentException if the node is already part of a partition
     */
    Partition isolate(@NonNull Node node);

    /**
     * Rejoins a node with the network. Restores connections that were active before isolation.
     *
     * @param node the node to rejoin
     */
    void rejoin(@NonNull Node node);

    /**
     * Checks if a node is currently isolated from the network.
     *
     * @param node the node to check
     * @return true if the node is isolated, false otherwise
     */
    boolean isIsolated(@NonNull Node node);

    /**
     * Restore the network connectivity to its original/default state. Removes all partitions, cliques, and custom
     * connection settings. The defaults are defined by the {@link Topology} of the network.
     */
    void restoreConnectivity();

    /**
     * Updates a single property of the configuration for every node in the network. Can only be invoked when no nodes
     * in the network are running.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code Network} instance for method chaining
     */
    Network withConfigValue(@NonNull String key, @NonNull String value);

    /**
     * Updates a single property of the configuration for every node in the network. Can only be invoked when no nodes
     * in the network are running.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code Network} instance for method chaining
     */
    Network withConfigValue(@NonNull String key, int value);

    /**
     * Updates a single property of the configuration for every node in the network. Can only be invoked when no nodes
     * in the network are running.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code Network} instance for method chaining
     */
    Network withConfigValue(@NonNull String key, long value);

    /**
     * Updates a single property of the configuration for every node in the network. Can only be invoked when no nodes
     * in the network are running.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code Network} instance for method chaining
     */
    Network withConfigValue(@NonNull String key, boolean value);

    /**
     * Updates a single property of the configuration for every node in the network. Can only be invoked when no nodes
     * in the network are running.
     *
     * @param key the key of the property
     * @param value the value of the property
     * @return this {@code Network} instance for method chaining
     */
    Network withConfigValue(@NonNull String key, @NonNull Path value);

    /**
     * Freezes the network.
     *
     * <p>This method sends a freeze transaction to one of the active nodes with a freeze time shortly after the
     * current time. The method returns once all nodes entered the
     * {@link org.hiero.consensus.model.status.PlatformStatus#FREEZE_COMPLETE} state.
     *
     * <p>It will wait for a environment-specific timeout before throwing an exception if the nodes do not reach the
     * {@code FREEZE_COMPLETE} state. The default can be overridden by calling {@link #withTimeout(Duration)}.
     */
    void freeze();

    /**
     * Triggers a catastrophic ISS. All nodes in the network will calculate different hashes for an upcoming round.
     */
    void triggerCatastrophicIss();

    /**
     * Shuts down the network. The nodes are killed immediately. No attempt is made to finish any outstanding tasks or
     * preserve any state. Once shutdown, it is possible to change the configuration etc. before resuming the network
     * with {@link #start()}.
     *
     * <p>The method will wait for an environment-specific timeout before throwing an exception if the nodes cannot be
     * killed. The default can be overridden by calling {@link #withTimeout(Duration)}.
     */
    void shutdown();

    /**
     * Allows to override the default timeout for network operations.
     *
     * @param timeout the duration to wait before considering the operation as failed
     * @return an instance of {@link AsyncNetworkActions} that can be used to perform network actions
     */
    @NonNull
    AsyncNetworkActions withTimeout(@NonNull Duration timeout);

    /**
     * Sets the version of the network.
     *
     * <p>This method sets the version of all nodes currently added to the network. Please note that the new version
     * will become effective only after a node is (re-)started.
     *
     * @param version the semantic version to set for the network
     * @see Node#version(SemanticVersion)
     */
    void version(@NonNull SemanticVersion version);

    /**
     * This method updates the version of all nodes in the network to trigger a "config only upgrade" on the next
     * restart.
     *
     * <p>Please note that the new version will become effective only after a node is (re-)started.
     *
     * @see Node#bumpConfigVersion()
     */
    void bumpConfigVersion();

    /**
     * Creates a new result with all the consensus rounds of all nodes that are currently in the network.
     *
     * @return the consensus rounds of the filtered nodes
     */
    @NonNull
    MultipleNodeConsensusResults newConsensusResults();

    /**
     * Creates a new result with all the log results of all nodes that are currently in the network.
     *
     * @return the log results of the nodes
     */
    @NonNull
    MultipleNodeLogResults newLogResults();

    /**
     * Creates a new result with all the status progression results of all nodes that are currently in the network.
     *
     * @return the status progression results of the nodes
     */
    @NonNull
    MultipleNodePlatformStatusResults newPlatformStatusResults();

    /**
     * Creates a new result with all the PCES file results of all nodes that are currently in the network.
     *
     * @return the PCES files created by the nodes
     */
    @NonNull
    MultipleNodePcesResults newPcesResults();

    /**
     * Creates a new result with all node reconnect results of all nodes that are currently in the network.
     *
     * @return the results of node reconnects
     */
    @NonNull
    MultipleNodeReconnectResults newReconnectResults();

    /**
     * Creates a new result with all marker file results of all nodes that are currently in the network.
     *
     * @return the marker file results of the nodes
     */
    @NonNull
    MultipleNodeMarkerFileResults newMarkerFileResults();

    /**
     * Checks if a node is behind compared to a strong minority of the network. A node is considered behind a peer when
     * its minimum non-ancient round is older than the peer's minimum non-expired round.
     *
     * @param maybeBehindNode the node to check behind status for
     * @return {@code true} if the node is behind by node weight, {@code false} otherwise
     * @see com.swirlds.platform.gossip.shadowgraph.SyncFallenBehindStatus
     */
    boolean nodeIsBehindByNodeWeight(@NonNull Node maybeBehindNode);

    /**
     * Checks if a node is behind compared to a fraction of peers in the network. A node is considered behind a peer
     * when its minimum non-ancient round is older than the peer's minimum non-expired round.
     *
     * @param maybeBehindNode the node to check behind status for
     * @param fraction the fraction of peers to consider for the behind check
     * @return {@code true} if the node is behind by the specified fraction of peers, {@code false} otherwise
     * @see com.swirlds.platform.gossip.shadowgraph.SyncFallenBehindStatus
     */
    boolean nodeIsBehindByNodeCount(@NonNull Node maybeBehindNode, double fraction);

    /**
     * Checks if all nodes in the network are in the specified {@link PlatformStatus}.
     *
     * @param status the status to check against
     * @return {@code true} if all nodes are in the specified status, {@code false} otherwise
     */
    default boolean allNodesInStatus(@NonNull final PlatformStatus status) {
        return nodes().stream().allMatch(node -> node.platformStatus() == status);
    }

    /**
     * Checks if all nodes in the network are {@link PlatformStatus#ACTIVE}.
     *
     * @return {@code true} if all nodes are active, {@code false} otherwise
     */
    default boolean allNodesAreActive() {
        return allNodesInStatus(PlatformStatus.ACTIVE);
    }
}
