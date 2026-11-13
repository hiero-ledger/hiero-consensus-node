// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation.duplicates;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;

/**
 * Is told about the duplicate copies of events that production gossip would send, but the simulated network does not,
 * see {@link DuplicateTracking}.
 */
@FunctionalInterface
public interface DuplicateListener {

    /**
     * Called for duplicate copies of an event that production gossip would send to one node. Only called for the nodes
     * the duplicates are estimated for.
     *
     * @param receiver        the node the duplicate copies are sent to
     * @param event           the event, which must not be modified
     * @param syncCopies      the number of duplicate copies sent by sync
     * @param broadcastCopies the number of duplicate copies broadcast by the creator of the event; together with
     *                        {@code syncCopies} at least one
     */
    void onDuplicates(@NonNull NodeId receiver, @NonNull PlatformEvent event, int syncCopies, int broadcastCopies);
}
