// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;

/**
 * Builds rosters for tests of the simulated network.
 */
public final class TestRosters {

    private TestRosters() {}

    /**
     * Creates a roster of the nodes of the given event receivers, in node id order, as the rosters of a real network
     * are.
     *
     * @param eventReceivers the event receivers, in any order
     * @return a roster with one entry of weight 1 for the node of every event receiver
     */
    @NonNull
    public static RosterWrapper rosterOf(@NonNull final Collection<? extends EventReceiver> eventReceivers) {
        return RosterWrapper.of(Roster.newBuilder()
                .rosterEntries(eventReceivers.stream()
                        .map(EventReceiver::getNodeId)
                        .distinct()
                        .sorted()
                        .map(nodeId -> RosterEntry.newBuilder()
                                .nodeId(nodeId.id())
                                .weight(1)
                                .build())
                        .toList())
                .build());
    }

    /**
     * Creates a roster of the given nodes, in the order given.
     *
     * @param nodeIds the ids of the nodes
     * @return a roster with one entry of weight 1 for every node
     */
    @NonNull
    public static RosterWrapper rosterOf(@NonNull final NodeId... nodeIds) {
        final RosterEntry[] entries = new RosterEntry[nodeIds.length];
        for (int i = 0; i < nodeIds.length; i++) {
            entries[i] =
                    RosterEntry.newBuilder().nodeId(nodeIds[i].id()).weight(1).build();
        }
        return RosterWrapper.of(Roster.newBuilder().rosterEntries(entries).build());
    }
}
