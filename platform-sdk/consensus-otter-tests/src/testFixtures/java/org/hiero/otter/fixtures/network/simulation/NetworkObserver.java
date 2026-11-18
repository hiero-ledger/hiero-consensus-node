// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Follows the gossip of a {@link SimulatedNetworkConnectivity} without taking part in it, for an add-on that needs to
 * know what the nodes do, such as a measurement. It reads the rest of what it needs from a {@link NetworkView}. It is
 * added with {@link SimulatedNetworkConnectivity#addObserver(NetworkObserver)}.
 *
 * <p>Nodes are identified by their rank, which is their index in the roster. Times are nanoseconds since the start of
 * the simulation.
 */
public interface NetworkObserver {

    /**
     * Called when a node submits an event to the network for the first time, which a node does once the event has
     * passed its intake, or once it has created it.
     *
     * @param event     the submitted instance of the event, which must not be modified
     * @param submitter the rank of the node that submits it
     * @param now       the time it is submitted
     */
    void eventSubmitted(@NonNull PlatformEvent event, int submitter, long now);
}
