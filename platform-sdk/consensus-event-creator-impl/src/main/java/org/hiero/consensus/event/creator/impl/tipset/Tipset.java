// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.event.NonDeterministicGeneration;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;

/**
 * Represents a slice of the hashgraph, containing one "tip" from each event creator.
 */
public class Tipset {

    private final Roster roster;

    /**
     * The tip generations, indexed by node index.
     */
    private final long[] tips;

    /**
     * Create an empty tipset.
     *
     * @param roster the current address book
     */
    public Tipset(@NonNull final Roster roster) {
        this.roster = Objects.requireNonNull(roster);
        tips = new long[roster.rosterEntries().size()];

        Arrays.fill(tips, NonDeterministicGeneration.GENERATION_UNDEFINED);
    }

    /**
     * Build an empty tipset (i.e. where all generations are {@link EventConstants#GENERATION_UNDEFINED}) using another
     * tipset as a template.
     *
     * @param tipset the tipset to use as a template
     * @return a new empty tipset
     */
    private static @NonNull Tipset buildEmptyTipset(@NonNull final Tipset tipset) {
        return new Tipset(tipset.roster);
    }

    /**
     * <p>
     * Merge a list of tipsets together.
     *
     * <p>
     * The generation for each node ID will be equal to the maximum generation found for that node ID from all source
     * tipsets.
     * In the case of empty list, a new Tipset instance with the current roster will be returned.
     *
     * @param tipsets the tipsets to merge, tipsets must be constructed from the same roster or
     *                else this method has undefined behavior
     * @return a new tipset
     */
    public @NonNull Tipset merge(@NonNull final List<Tipset> tipsets) {
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
     * Get the tip generation for a given node. If the node is not in the roster or no event from that node is know,
     * return {@link EventConstants#GENERATION_UNDEFINED}.
     *
     * @param nodeId the node in question
     * @return the tip generation for the node
     */
    public long getTipGenerationForNode(@NonNull final NodeId nodeId) {
        final int index = RosterUtils.getIndex(roster, nodeId.id());
        if (index == -1) {
            return NonDeterministicGeneration.GENERATION_UNDEFINED;
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
     * @param creator    the node ID of the creator of the event
     * @param generation the generation of the event
     * @return this object
     */
    public @NonNull Tipset advance(@NonNull final NodeId creator, final long generation) {
        final int index = RosterUtils.getIndex(roster, creator.id());
        tips[index] = Math.max(tips[index], generation);
        return this;
    }

    /**
     * <p>
     * Get the combined weight of all nodes which experienced a tip advancement between this tipset and another tipset.
     * Note that this method ignores advancement contributions from this node.
     * </p>
     *
     * <p>
     * A tip advancement is defined as an increase in the tip generation for a node ID. The exception to this rule is
     * that an increase in generation for the self ID is never counted as a tip advancement. The tip advancement weight
     * is defined as the sum of all remaining tip advancements after being appropriately weighted.
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

        final int selfIndex = RosterUtils.getIndex(roster, selfId.id());
        for (int index = 0; index < tips.length; index++) {
            if (index == selfIndex) {
                // We don't consider self advancement here, since self advancement does nothing to help consensus.
                continue;
            }

            if (this.tips[index] < that.tips[index]) {
                final RosterEntry address = roster.rosterEntries().get(index);

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
    public String toString() {
        final StringBuilder sb = new StringBuilder("(");
        for (int index = 0; index < tips.length; index++) {
            sb.append(roster.rosterEntries().get(index).nodeId()).append(":").append(tips[index]);
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
