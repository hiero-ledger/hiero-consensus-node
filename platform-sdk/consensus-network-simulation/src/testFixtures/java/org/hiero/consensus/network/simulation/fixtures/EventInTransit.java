// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * An event that is in transit between nodes in the network.
 *
 * @param event       the event being transmitted
 * @param arrivalTime the time the event is scheduled to arrive at its destination
 */
public record EventInTransit(
        @NonNull PlatformEvent event, @NonNull Instant arrivalTime) implements Comparable<EventInTransit> {
    @Override
    public int compareTo(@NonNull final EventInTransit that) {
        return arrivalTime.compareTo(that.arrivalTime);
    }
}
