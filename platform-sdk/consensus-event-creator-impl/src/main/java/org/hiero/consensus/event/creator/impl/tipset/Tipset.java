// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterEntryWrapper;
import org.hiero.consensus.model.roster.RosterWrapper;

/**
 * Represents a slice of the hashgraph, containing one "tip" from each event creator: the latest event of that creator in
 * the slice. A tip is represented by its ordering key, which can only be compared with the ordering keys of events of
 * the same creator, where a later event has a higher key.
 */
public class Tipset {

    private final RosterWrapper roster;

    /**
     * The ordering keys of the tips, indexed by node index.
     */
    private final long[] tips;

    /**
     * Create an empty tipset.
     *
     * @param roster the current address book
     */
    public Tipset(@NonNull final RosterWrapper roster) {
        this.roster = Objects.requireNonNull(roster);
        tips = new long[roster.size()];

        Arrays.fill(tips, EventConstants.SEQUENCE_NUMBER_UNDEFINED);
    }

    /**
     * Build an empty tipset (i.e. where all ordering keys are {@link EventConstants#SEQUENCE_NUMBER_UNDEFINED}) using
     * another tipset as a template.
     *
     * @param tipset the tipset to use as a template
     * @return a new empty tipset
     */
    @NonNull
    private static Tipset buildEmptyTipset(@NonNull final Tipset tipset) {
        return new Tipset(tipset.roster);
    }

    /**
     * <p>
     * Merge a list of tipsets together.
     *
     * <p>
     * The ordering key for each node ID will be equal to the maximum ordering key found for that node ID from all
     * source tipsets.
     * In the case of empty list, a new Tipset instance with the active roster will be returned.
     *
     * @param tipsets the tipsets to merge, tipsets must be constructed from the same roster or
     *                else this method has undefined behavior
     * @return a new tipset
     */
    @NonNull
    public Tipset merge(@NonNull final List<Tipset> tipsets) {
        if (tipsets.isEmpty()) {
            return new Tipset(roster);
        }

        final int length = this.tips.length;
        final Tipset newTipset = buildEmptyTipset(this);

        for (int index = 0; index < length; index++) {
            long max = this.tips[index];
            for (final Tipset tipSet : tipsets) {
                max = Math.max(max, tipSet.tips[index]);
            }
            newTipset.tips[index] = max;
        }

        return newTipset;
    }

    /**
     * Get the ordering key of the tip for a given node. If the node is not in the roster or no event from that node is
     * known, return {@link EventConstants#SEQUENCE_NUMBER_UNDEFINED}.
     *
     * @param nodeId the node in question
     * @return the ordering key of the tip for the node
     */
    public long getTipOrderingKeyForNode(@NonNull final NodeId nodeId) {
        final int index = roster.index(nodeId);
        if (index == -1) {
            return EventConstants.SEQUENCE_NUMBER_UNDEFINED;
        }
        return tips[index];
    }

    /**
     * Get the number of tips currently being tracked.
     *
     * @return the number of tips
     */
    public int size() {
        return tips.length;
    }

    /**
     * Advance a single tip within the tipset.
     *
     * @param creator     the node ID of the creator of the event
     * @param orderingKey the ordering key of the event
     * @return this object
     */
    @NonNull
    public Tipset advance(@NonNull final NodeId creator, final long orderingKey) {
        final int index = roster.index(creator);
        tips[index] = Math.max(tips[index], orderingKey);
        return this;
    }

    /**
     * <p>
     * Get the combined weight of all nodes which experienced a tip advancement between this tipset and another tipset.
     * Note that this method ignores advancement contributions from this node.
     * </p>
     *
     * <p>
     * A tip advancement is defined as an increase in the ordering key of the tip for a node ID. The exception to this
     * rule is that an increase for the self ID is never counted as a tip advancement. The tip advancement weight is
     * defined as the sum of all remaining tip advancements after being appropriately weighted.
     * </p>
     *
     * <p>
     * Advancements of non-zero stake nodes are tracked via {@link TipsetAdvancementWeight#advancementWeight()}, while
     * advancements of zero stake nodes are tracked via {@link TipsetAdvancementWeight#zeroWeightAdvancementCount()}.
     *
     * @param selfId compute the advancement weight relative to this node ID
     * @param that   the tipset to compare to
     * @return the tipset advancement weight
     */
    @NonNull
    public TipsetAdvancementWeight getTipAdvancementWeight(@NonNull final NodeId selfId, @NonNull final Tipset that) {
        long nonZeroWeight = 0;
        long zeroWeightCount = 0;

        final int selfIndex = roster.index(selfId);
        for (int index = 0; index < tips.length; index++) {
            if (index == selfIndex) {
                // We don't consider self advancement here, since self advancement does nothing to help consensus.
                continue;
            }

            if (this.tips[index] < that.tips[index]) {
                final RosterEntryWrapper address = roster.rosterEntryAtIndex(index);

                if (address.weight() == 0) {
                    zeroWeightCount += 1;
                } else {
                    nonZeroWeight += address.weight();
                }
            }
        }

        return TipsetAdvancementWeight.of(nonZeroWeight, zeroWeightCount);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String toString() {
        final StringBuilder sb = new StringBuilder("(");
        for (int index = 0; index < tips.length; index++) {
            sb.append(roster.nodeIdAtIndex(index)).append(":").append(tips[index]);
            if (index < tips.length - 1) {
                sb.append(", ");
            }
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean equals(final Object o) {
        if (!(o instanceof final Tipset tipset)) {
            return false;
        }

        return roster.equals(tipset.roster) && Arrays.equals(tips, tipset.tips);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        int result = roster.hashCode();
        result = 31 * result + Arrays.hashCode(tips);
        return result;
    }
}
