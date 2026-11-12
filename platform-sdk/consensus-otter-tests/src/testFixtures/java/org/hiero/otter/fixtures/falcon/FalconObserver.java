// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.network.simulation.SimulatedNetworkConnectivity;

/**
 * Follows a {@link FalconNetwork} for a measurement that is not part of Falcon itself, such as the
 * creation-to-consensus latency in {@link org.hiero.otter.fixtures.falcon.latency}. It is set with
 * {@link FalconNetwork#observe(FalconObserver)}; a network without one does not pay for it.
 *
 * <p>What the nodes gossip can be followed by adding a
 * {@link org.hiero.otter.fixtures.network.simulation.NetworkObserver} to the simulated network when it starts, see
 * {@link #networkStarting(SimulatedNetworkConnectivity, Instant)}.
 *
 * <p>Falcon drives all nodes from a single thread, so the methods are never called concurrently. Every method does
 * nothing by default.
 */
public interface FalconObserver {

    /**
     * Called when a node is added to the network, before the network starts.
     *
     * @param nodeId   the id of the node
     * @param fullNode {@code true} for a full node, which runs a consensus engine, {@code false} for a mini-node
     */
    default void nodeAdded(@NonNull final NodeId nodeId, final boolean fullNode) {}

    /**
     * Called right before the network starts, once the simulated network that connects the nodes exists. The nodes
     * start right after, at the same simulated time.
     *
     * @param network the simulated network, which observers of the gossip can be added to
     * @param now     the current simulated time
     */
    default void networkStarting(@NonNull final SimulatedNetworkConnectivity network, @NonNull final Instant now) {}

    /**
     * Called for every consensus round a full node reaches, with the node's own instance of the round, as produced by
     * its consensus engine.
     *
     * @param nodeId the id of the full node
     * @param round  the round, which must not be modified
     */
    default void consensusRound(@NonNull final NodeId nodeId, @NonNull final ConsensusRound round) {}
}
