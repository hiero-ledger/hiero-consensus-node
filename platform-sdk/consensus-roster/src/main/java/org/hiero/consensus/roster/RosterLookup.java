// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import org.hiero.base.utility.Threshold;
import org.hiero.consensus.model.node.NodeId;

/**
 * Helper class for looking up information about a roster.
 */
public class RosterLookup {
    /** the roster to look up */
    private final Roster roster;
    /** the total weight of all roster entries. */
    private final long rosterTotalWeight;
    /** true if at least one node has a supermajority of the weight. */
    private final boolean nodeHasSupermajorityWeight;
    /** roster indices map. */
    private final Map<Long, Integer> rosterIndicesMap;

    /**
     * Constructor.
     *
     * @param roster the roster to look up
     */
    public RosterLookup(@NonNull final Roster roster) {
        this.roster = roster;
        this.rosterTotalWeight = RosterUtils.computeTotalWeight(roster);
        this.rosterIndicesMap = RosterUtils.toIndicesMap(roster);
        final long maxWeight = roster.rosterEntries().stream()
                .mapToLong(RosterEntry::weight)
                .max()
                .orElse(0);
        this.nodeHasSupermajorityWeight = Threshold.SUPER_MAJORITY.isSatisfiedBy(maxWeight, rosterTotalWeight);
    }

    /**
     * @return the roster
     */
    public @NonNull Roster getRoster() {
        return roster;
    }

    /**
     * @return the total weight of all roster entries
     */
    public long rosterTotalWeight() {
        return rosterTotalWeight;
    }

    /**
     * @return true if at least one node has a supermajority of the weight
     */
    public boolean nodeHasSupermajorityWeight() {
        return nodeHasSupermajorityWeight;
    }

    /**
     * @return the number of members in the roster
     */
    public int numMembers() {
        return roster.rosterEntries().size();
    }

    /**
     * Check if a node ID corresponds to a given index in the roster
     *
     * @param nodeId the ID of the node
     * @param index  the index to check
     * @return true if the node ID corresponds to the index, false otherwise
     */
    public boolean isIdAtIndex(@NonNull final NodeId nodeId, final int index) {
        if (index < 0 || index >= roster.rosterEntries().size()) {
            return false;
        }
        return roster.rosterEntries().get(index).nodeId() == nodeId.id();
    }

    /**
     * Get the weight of a node by its ID
     *
     * @param nodeId the ID of the node
     * @return the weight of the node, or 0 if the node is not in the roster
     */
    public long getWeight(@NonNull final NodeId nodeId) {
        final Integer index = rosterIndicesMap.get(nodeId.id());
        if (index == null) {
            return 0;
        }
        final RosterEntry entry = roster.rosterEntries().get(index);
        if (entry == null) {
            return 0;
        }
        return entry.weight();
    }

    /**
     * Get the weight of a node by its index
     *
     * @param nodeIndex the index of the node
     * @return the weight of the node
     */
    public long getWeight(final int nodeIndex) {
        return roster.rosterEntries().get(nodeIndex).weight();
    }

    /**
     * Get the index of a node by its ID
     *
     * @param nodeId the ID of the node
     * @return the index of the node
     * @throws IllegalArgumentException if the node ID is not found in the roster
     */
    public int getRosterIndex(@NonNull final NodeId nodeId) {
        final Integer index = rosterIndicesMap.get(nodeId.id());
        if (index == null) {
            throw new IllegalArgumentException("Node ID not found in roster: " + nodeId.id());
        }
        return index;
    }
}
