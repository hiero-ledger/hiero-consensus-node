// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * An event window that is in transit between nodes in the network.
 *
 * @param eventWindow the event window being transmitted
 * @param sender      the node whose event window this is
 * @param arrivalTime the time the event window is scheduled to arrive at its destination
 */
public record EventWindowInTransit(
        @NonNull EventWindow eventWindow,
        @NonNull NodeId sender,
        @NonNull Instant arrivalTime) {}
