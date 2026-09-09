// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.roster;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.hiero.base.utility.Threshold;
import org.hiero.consensus.model.node.NodeId;

/**
 * A wrapper around a {@link Roster} that provides additional functionality and convenience methods.
 */
public class RosterWrapper {

    /** The underlying {@link Roster} instance. */
    @NonNull
    private final Roster roster;

    /** The list of {@link RosterEntryWrapper} instances in this roster. */
    @NonNull
    private final List<RosterEntryWrapper> rosterEntries;

    /**
     * This array is used to find the index of an entry based on its NodeId (stored as a long).
     * We use a lookup table, because searching in a small array of primitives
     * is usually faster than HashMap-lookups.
     */
    @NonNull
    private final long[] idLookupTable;

    /** the total weight of all entries in this roster. */
    private final long totalWeight;

    /** true if at least one node has a supermajority of the weight. */
    private final boolean nodeHasSupermajorityWeight;

    /**
     * Constructs a new {@link RosterWrapper} instance.
     *
     * @param roster the {@link Roster} to wrap
     */
    private RosterWrapper(@NonNull final Roster roster) {
        this.roster = roster;
        rosterEntries =
                roster.rosterEntries().stream().map(RosterEntryWrapper::new).toList();
        idLookupTable =
                roster.rosterEntries().stream().mapToLong(RosterEntry::nodeId).toArray();
        totalWeight =
                rosterEntries.stream().mapToLong(RosterEntryWrapper::weight).sum();
        nodeHasSupermajorityWeight = rosterEntries.stream()
                .anyMatch(entry -> Threshold.SUPER_MAJORITY.isSatisfiedBy(entry.weight(), totalWeight));
    }

    /**
     * Creates a new {@link RosterWrapper} instance from the given {@link Roster}.
     *
     * @param roster the {@link Roster} to wrap
     * @return a new {@link RosterWrapper} instance
     */
    @NonNull
    public static RosterWrapper of(@NonNull final Roster roster) {
        return new RosterWrapper(roster);
    }

    /**
     * Returns the list of {@link RosterEntryWrapper} instances in this roster.
     *
     * @return the list of {@link RosterEntryWrapper} instances
     */
    @NonNull
    public List<RosterEntryWrapper> rosterEntries() {
        return rosterEntries;
    }

    /**
     * Returns the number of entries in this roster.
     *
     * @return the number of entries
     */
    public int size() {
        return rosterEntries().size();
    }

    /**
     * Returns the index of the given {@link NodeId} in this roster, or -1 if the node is not present.
     *
     * @param nodeId the {@link NodeId} to look up
     * @return the index of the given {@link NodeId}, or {@code -1} if not present
     */
    public int getIndex(@NonNull final NodeId nodeId) {
        final long id = nodeId.id();
        for (int i = 0, n = idLookupTable.length; i < n; i++) {
            if (idLookupTable[i] == id) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the total weight of all entries in this roster.
     *
     * @return the total weight
     */
    public long totalWeight() {
        return totalWeight;
    }

    /**
     * Returns {@code true} if a node in this roster has a supermajority of the weight.
     *
     * @return {@code true} if a node has a supermajority of the weight, {@code false} otherwise
     */
    public boolean nodeHasSupermajorityWeight() {
        return nodeHasSupermajorityWeight;
    }

    /**
     * Returns the {@link RosterEntryWrapper} for the given {@link NodeId}.
     *
     * @param nodeId the {@link NodeId} to look up
     * @return the corresponding {@link RosterEntryWrapper}
     * @throws IllegalArgumentException if the {@link NodeId} is not present in this roster
     */
    @NonNull
    public RosterEntryWrapper getRosterEntry(@NonNull final NodeId nodeId) {
        final int index = getIndex(nodeId);
        if (index == -1) {
            throw new IllegalArgumentException("NodeId " + nodeId + " is not in the roster");
        }
        return rosterEntries.get(index);
    }

    /**
     * Checks if the given {@link NodeId} is present in this roster.
     *
     * @param nodeId the {@link NodeId} to check
     * @return {@code true} if the {@link NodeId} is present, {@code false} otherwise
     */
    public boolean contains(@NonNull final NodeId nodeId) {
        return getIndex(nodeId) != -1;
    }

    /**
     * Returns the underlying {@link Roster} instance.
     *
     * @return the underlying {@link Roster}
     */
    @NonNull
    public Roster toPbj() {
        return roster;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(@Nullable final Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final RosterWrapper that = (RosterWrapper) o;
        return roster.equals(that.roster);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return roster.hashCode();
    }
}
