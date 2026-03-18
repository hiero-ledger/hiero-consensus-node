// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.gui.internal.hashgraph.util;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.roster.RosterUtils;

/**
 * Metadata that is calculated based on a {@link Roster} that is used to aid in drawing a hashgraph
 */
public class RosterMetadata {
    /** the roster that this metadata is based on */
    private final Roster roster;
    /** the number of members in the roster */
    private final int numMembers;
    /** the labels of all the members */
    private final String[] memberLabels;

    public RosterMetadata(@NonNull final Roster roster) {
        this.roster = Objects.requireNonNull(roster, "roster must not be null");
        final int m = roster.rosterEntries().size();
        numMembers = m;
        memberLabels = new String[m];
        for (int i = 0; i < m; i++) {
            memberLabels[i] = "ID:%d W:%d"
                    .formatted(
                            roster.rosterEntries().get(i).nodeId(),
                            roster.rosterEntries().get(i).weight());
        }
    }

    /**
     * @return the total number of memebers
     */
    public int getNumMembers() {
        return numMembers;
    }

    /**
     * @return the number of columns to draw
     */
    public int getNumColumns() {
        return numMembers;
    }

    /**
     * find the column for e
     */
    public int mems2col(@NonNull final EventImpl e) {
        Objects.requireNonNull(e, "e must not be null");
        return RosterUtils.getIndex(roster, e.getCreatorId().id());
    }

    /**
     * @param i
     * 		member index
     * @return the label of the member with the provided index
     */
    public String getLabel(final int i) {
        return memberLabels[i];
    }
}
