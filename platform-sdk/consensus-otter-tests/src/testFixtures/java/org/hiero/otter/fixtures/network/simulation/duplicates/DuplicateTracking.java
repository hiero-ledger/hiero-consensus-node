// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation.duplicates;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.network.simulation.NetworkObserver;
import org.hiero.otter.fixtures.network.simulation.NetworkView;

/**
 * Estimates the duplicate copies of events that production gossip would send in a simulated network.
 *
 * <p>The simulated network sends each event to each node once, so it sends almost no duplicates, and the hold-back of
 * sync that keeps them down in production has no visible benefit in the simulation. The estimate puts a number on them
 * by replaying the syncs production would run, see {@link SyncDuplicateEstimator}. It follows the network as a
 * {@link NetworkObserver}, which reads it through its {@link NetworkView}, and does not change what the network does.
 *
 * <p>The estimate is expensive, so it is only made for the nodes that are asked for, and not at all unless the
 * estimator is added as an observer of the network, for example with
 * {@code SimulatedNetworkConnectivity.addObserver(NetworkObserver)}.
 */
public final class DuplicateTracking {

    private DuplicateTracking() {}

    /**
     * Creates an estimator of the duplicates production gossip would send to some nodes of a network, which tells a
     * listener about them once it is added as an observer of the network. It must be added before any event is
     * submitted.
     *
     * @param network   the network, as the estimator reads it
     * @param receivers the nodes to estimate the duplicates sent to
     * @param listener  is told about the duplicates
     * @return the estimator, to be added as an observer of the network
     * @throws IllegalArgumentException if a receiver is not part of the network
     */
    @NonNull
    public static NetworkObserver estimator(
            @NonNull final NetworkView network,
            @NonNull final Collection<NodeId> receivers,
            @NonNull final DuplicateListener listener) {
        requireNonNull(listener);
        final boolean[] estimatedFor = new boolean[network.nodeCount()];
        for (final NodeId receiver : receivers) {
            final int rank = network.rankOf(receiver);
            if (rank < 0) {
                throw new IllegalArgumentException("Node " + receiver + " is not part of the network");
            }
            estimatedFor[rank] = true;
        }
        return new SyncDuplicateEstimator(network, estimatedFor, listener);
    }
}
