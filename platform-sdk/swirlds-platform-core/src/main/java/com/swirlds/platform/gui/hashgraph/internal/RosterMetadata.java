// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.hashgraph.internal;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
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

    // the following allow each member to have multiple columns so lines don't cross
    /** number of columns (more than number of members if preventCrossings) */
    private final int numColumns;
    /** mems2col[a][b] = which member-b column is adjacent to some member-a column */
    private final int[][] mems2col;
    /** col2mems[c][0] = the member for column c, col2mems[c][1] = second member or -1 if none */
    private final int[][] col2mems;

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

        // fix corner cases missed by the formulas here
        if (numMembers == 1) {
            numColumns = 1;
            col2mems = new int[][] {{0, -1}};
            mems2col = new int[][] {{0}};
            return;
        } else if (numMembers == 2) {
            numColumns = 2;
            col2mems = new int[][] {{0, -1}, {1, -1}};
            mems2col = new int[][] {{0, 1}, {0, 0}};
            return;
        }

        numColumns = m;
        mems2col = new int[m][m];
        col2mems = new int[numColumns][2];
        for (int i = 0; i < m; i++) {
            col2mems[i][0] = i;
            col2mems[i][1] = -1;
            for (int j = 0; j < m; j++) {
                mems2col[i][j] = j;
            }
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
        return numColumns;
    }

    /**
     * find the column for e
     */
    public int mems2col(@NonNull final EventImpl e) {
        Objects.requireNonNull(e, "e must not be null");
        // To support Noncontiguous NodeId in the roster,
        // the mems2col array is now based on indexes of NodeIds in the roster.
        final int eIndex = RosterUtils.getIndex(roster, e.getCreatorId().id());
        // there is no e1, so pick one of the e2 columns arbitrarily (next to 0 or 1). If there is only 1
        // member, avoid the array out of bounds exception
        return mems2col[eIndex == 0 ? getNumColumns() == 1 ? 0 : 1 : 0][eIndex];
    }

    /**
     * @param i
     * 		member index
     * @return the label of the member with the provided index
     */
    public String getLabel(final int i) {
        if (col2mems[i][1] == -1) {
            return "" + memberLabels[col2mems[i][0]];
        } else {
            return "" + memberLabels[col2mems[i][0]] + "|" + memberLabels[col2mems[i][1]];
        }
    }
}
