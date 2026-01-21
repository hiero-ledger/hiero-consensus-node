package com.swirlds.platform.consensus;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import org.hiero.base.utility.Threshold;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;

public class RosterLookup{
    /** the only roster currently, until roster changes are implemented */
    private final Roster roster;
    /** the total weight of all roster entries. */
    private final long rosterTotalWeight;
    /** true if at least one node has a supermajority of the weight. */
    private final boolean nodeHasSupermajorityWeight;
    /** roster indices map. */
    private final Map<Long, Integer> rosterIndicesMap;

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

    public Roster getRoster() {
        return roster;
    }

    public long rosterTotalWeight() {
        return rosterTotalWeight;
    }

    public boolean nodeHasSupermajorityWeight() {
        return nodeHasSupermajorityWeight;
    }

    public int numMembers() {
        return roster.rosterEntries().size();
    }

    public boolean idEqualsIndex(@NonNull final NodeId nodeId, final int index) {
        if(index < 0 || index >= roster.rosterEntries().size()) {
            return false;
        }
        return roster.rosterEntries().get(index).nodeId() == nodeId.id();
    }

    /**
     * Get the weigh of a node by its ID
     * @param nodeId the ID of the node
     * @return the weight of the node, or 0 if the node is not in the address book
     */
    public long getWeight(@NonNull final NodeId nodeId) {
        final RosterEntry entry = roster.rosterEntries().get(rosterIndicesMap.get(nodeId.id()));
        if (entry == null) {
            return 0;
        }
        return entry.weight();
    }

    /**
     * Get the weight of a node by its index
     * @param nodeIndex the index of the node
     * @return the weight of the node
     */
    public long getWeight(final int nodeIndex) {
        return roster.rosterEntries().get(nodeIndex).weight();
    }

    public int getRosterIndex(@NonNull final NodeId nodeId) {
        return rosterIndicesMap.get(nodeId.id());//TODO handle null
    }
}
