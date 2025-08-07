// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.common.test.fixtures.WeightGenerator;
import com.swirlds.common.test.fixtures.WeightGenerators;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.hiero.otter.fixtures.network.BandwidthLimit;
import org.hiero.otter.fixtures.network.Clique;
import org.hiero.otter.fixtures.network.Connection;
import org.hiero.otter.fixtures.network.GeoMeshTopology;
import org.hiero.otter.fixtures.network.LatencyRange;
import org.hiero.otter.fixtures.network.MeshTopology;
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
     * Adds a single node to the network.
     *
     * <p>How the node is connected to existing nodes and its latency, jitter, and bandwidth depend on the current topology.
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
     * <p>How the node is connected to existing nodes and its latency, jitter, and bandwidth depend on current topology.
     *
     * @param count the number of nodes to add
     * @return list of created nodes
     */
    @NonNull
    default List<Node> addNodes(final int count) {
        return topology().addNodes(count);
    }

    /**
     * Sets the weight generator for the network. The weight generator is used to assign weights to nodes.
     *
     * <p>If no weight generator is set, the default {@link WeightGenerators#GAUSSIAN} is used.
     *
     * @param weightGenerator the weight generator to use
     */
    void setWeightGenerator(@NonNull WeightGenerator weightGenerator);

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
     * Returns the {@link Topology} of the network.
     *
     * @return the topology of the network
     */
    @NonNull
    Topology topology();

    /**
     * Sets the {@link Topology} of the network.
     *
     * <p>This method replaces the current topology with the specified one. It is recommended to call this method before
     * adding any nodes to ensure that the nodes are included in the new topology.
     *
     * @param topology the new topology to set for the network
     */
    void setTopology(@NonNull Topology topology);

    /**
     * Creates a new {@link MeshTopology} for the network.
     *
     * @return a new instance of {@link MeshTopology} representing the mesh topology
     */
    @NonNull
    MeshTopology createMeshTopology();

    /**
     * Creates a new {@link GeoMeshTopology} for the network.
     *
     * @return a new instance of {@link GeoMeshTopology} representing the geographic mesh topology
     */
    @NonNull
    GeoMeshTopology createGeoMeshTopology();

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
    default List<Node> getNodes() {
        return topology().getNodes();
    }

    /**
     * Creates a network partition containing the specified nodes. Nodes within the partition remain connected to each
     * other but are disconnected from all nodes outside the partition.
     *
     * @param partition the nodes to include in the partition
     * @return the created Partition object
     */
    @NonNull
    Partition createPartition(@NonNull Collection<Node> partition);

    /**
     * Removes a partition and restores connectivity for its nodes. Only restores changes that were made by creating the
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
    Set<Partition> getPartitions();

    /**
     * Gets the partition containing the specified node.
     *
     * @param node the node to search for
     * @return the partition containing the node, or {@code null} if not in any partition
     */
    @Nullable
    Partition getPartitionContaining(@NonNull Node node);

    /**
     * Creates a clique with throttled external connections. Nodes within the clique have full connectivity, while
     * connections to external nodes are limited by the specified bandwidth.
     *
     * @param clique the nodes to include in the clique
     * @param externalBandwidth the bandwidth limit for external connections
     * @return the created ThrottledClique object
     */
    @NonNull
    default Clique createClique(@NonNull final Collection<Node> clique, @NonNull final BandwidthLimit externalBandwidth) {
        return createClique(clique, null, externalBandwidth);
    }

    /**
     * Creates a clique with increased latency to external connections. Nodes within the clique have normal
     * connectivity, while connections to external nodes have additional latency.
     *
     * @param clique the nodes to include in the clique
     * @param externalLatencyRange the latency range for external connections
     * @return the created SlowClique object
     */
    @NonNull
    default Clique createClique(@NonNull final Collection<Node> clique, @NonNull final LatencyRange externalLatencyRange) {
        return createClique(clique, externalLatencyRange, null);
    }

    /**
     * Creates a clique with increased latency and throttled bandwidth to external connections. Nodes within the clique
     * have normal connectivity, while connections to external nodes have additional latency and bandwidth limits.
     *
     * @param clique the nodes to include in the clique
     * @param externalLatencyRange the latency range for external connections, can be {@code null} if the current value
     * is desired
     * @param externalBandwidth the bandwidth limit for external connections, can be {@code null} if the current value
     * is desired
     * @return the created SlowClique object
     */
    @NonNull
    Clique createClique(@NonNull Collection<Node> clique, @Nullable final LatencyRange externalLatencyRange,
            @Nullable final BandwidthLimit externalBandwidth);

    /**
     * Removes a clique and restores normal connectivity for its nodes. Only restores changes that were made by creating
     * the clique.
     *
     * @param clique the clique to remove
     */
    void removeClique(@NonNull Clique clique);

    /**
     * Gets all currently active cliques.
     *
     * @return set of all active cliques
     */
    @NonNull
    Set<Clique> getCliques();

    /**
     * Gets the clique containing the specified node.
     *
     * @param node the node to search for
     * @return the clique containing the node, or null if not in any clique
     */
    @Nullable
    Clique getCliqueContaining(@NonNull Node node);

    /**
     * Returns a connection between two nodes in the network.
     *
     * @param node1 the first node
     * @param node2 the second node
     * @return the connection between the two nodes
     */
    @NonNull
    Connection connection(@NonNull Node node1, @NonNull Node node2);

    /**
     * Isolates a node from the network. Disconnects all connections to and from this node.
     *
     * @param node the node to isolate
     */
    void isolate(@NonNull Node node);

    /**
     * Rejoins a node with the network. Restores connections that were active before isolation.
     *
     * @param node the node to rejoin
     */
    void rejoin(@NonNull Node node);

    /**
     * Checks if a node is currently isolated from the network, either by calling {@link #isolate(Node)} for the
     * {@code node} or by calling {@link Connection#disconnect()} for each connection of the {@code node}.
     *
     * @param node the node to check
     * @return true if the node is isolated, false otherwise
     */
    boolean isIsolated(@NonNull Node node);

    /**
     * Sets the latency range for all connections from this node.
     *
     * <p>This method sets the latency for all connections from the specified node to the given latency range. If a
     * connection already has a custom latency set, it will be overridden by this method.
     *
     * @param node the node for which to set the latency
     * @param latencyRange the latency range to apply to all connections
     */
    void setLatencyForAllConnections(@NonNull Node node, @NonNull LatencyRange latencyRange);

    /**
     * Restores the default latency for all connections from this node. The default is determined by the topology.
     *
     * @param node the node for which to remove custom latencies
     */
    void restoreLatencyForAllConnections(@NonNull Node node);

    /**
     * Sets the bandwidth limit for all connections from this node.
     *
     * @param node the node for which to set the bandwidth limit
     * @param bandwidthLimit the bandwidth limit to apply to all connections
     */
    void setBandwidthForAllConnections(@NonNull Node node, @NonNull BandwidthLimit bandwidthLimit);

    /**
     * Restores the default bandwidth limit for all connections from this node. The default is determined by the topology.
     *
     * @param node the node for which to remove bandwidth limits
     */
    void restoreBandwidthLimitsForAllConnections(@NonNull Node node);

    /**
     * Restore the network connectivity to its original/default state. Removes all partitions, cliques, and custom
     * connection settings.
     */
    void restoreConnectivity();

    /**
     * Gets the total weight of the network. Always positive.
     *
     * @return the network weight
     */
    long getTotalWeight();

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
     * @see Node#setVersion(SemanticVersion)
     */
    void setVersion(@NonNull SemanticVersion version);

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
}
