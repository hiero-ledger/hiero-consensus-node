// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.gossip;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;

/**
 * An event that is in transit between nodes in the network.
 *
 * @param event       the event being transmitted
 * @param sender      the node that sent the event
 * @param arrivalTime the time the event is scheduled to arrive at its destination
 */
public record EventInTransit(@NonNull PlatformEvent event, @NonNull NodeId sender, @NonNull Instant arrivalTime)
        implements Comparable<EventInTransit> {
    @Override
    public int compareTo(@NonNull final EventInTransit that) {
        return arrivalTime.compareTo(that.arrivalTime);
    }
}
