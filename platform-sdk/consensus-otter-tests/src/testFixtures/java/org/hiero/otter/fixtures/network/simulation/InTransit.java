// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.consensus.model.node.NodeId;

/**
 * Something that has been transmitted onto the simulated network and is on its way to a receiver. It is what
 * {@link InFlightConnections} needs to know about whatever it holds, so that it can file it under the connection it
 * travels on and check that the connection's queue stays in arrival order.
 */
interface InTransit {

    /**
     * @return the node that sent this
     */
    @NonNull
    NodeId sender();

    /**
     * @return the time this is scheduled to arrive at its destination
     */
    @NonNull
    Instant arrivalTime();
}
