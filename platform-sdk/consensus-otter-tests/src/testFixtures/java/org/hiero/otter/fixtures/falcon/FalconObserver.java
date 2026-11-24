// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.network.simulation.SimulatedNetworkConnectivity;

/**
 * Follows a {@link FalconNetwork} for measurings extra things which arenot part of Falcon itself.
 * It is set with {@link FalconNetwork#registerObserver(FalconObserver)}
 *
 * Its main entry point is {@link #onConsensusRound(NodeId, ConsensusRound)}, which gets called every time a round
 * has reached consensus on a given <b>full</b> node. It can be used to observe timings, size of events, round
 * composition and many other things.
 * <p>
 * Other methods are called on startup, to help identify/understand the environment in which observation will be run.
 *
 * <p>What the nodes gossip can be followed by adding a
 * {@link org.hiero.otter.fixtures.network.simulation.NetworkObserver} to the simulated network when it starts, see
 * {@link #onNetworkStarting(SimulatedNetworkConnectivity, Instant)}.
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
    default void onNodeAdded(@NonNull final NodeId nodeId, final boolean fullNode) {}

    /**
     * Called right before the network starts, once the simulated network that connects the nodes exists. The nodes
     * start right after, at the same simulated time.
     *
     * @param network the simulated network, which observers of the gossip can be added to
     * @param now     the current simulated time
     */
    default void onNetworkStarting(@NonNull final SimulatedNetworkConnectivity network, @NonNull final Instant now) {}

    /**
     * Called for every consensus round a full node reaches, with the node's own instance of the round, as produced by
     * its consensus engine.
     *
     * @param nodeId the id of the full node
     * @param round  the round, which must not be modified
     */
    default void onConsensusRound(@NonNull final NodeId nodeId, @NonNull final ConsensusRound round) {}
}
