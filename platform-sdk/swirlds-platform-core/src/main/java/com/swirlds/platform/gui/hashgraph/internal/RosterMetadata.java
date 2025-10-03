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

    /**
     * In order to draw this "expanded" hashgraph (where each member has multiple columns and lines don't
     * cross), we need several data tables. So fill in four arrays: numMembers, mems2col, col2mems, and
     * names, if they haven't already been filled in, or if the number of members has changed.
     */
    public RosterMetadata(@NonNull final Roster roster, final boolean expand) {
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

        if (!expand) { // if unchecked so only one column per member, then the arrays are trivial
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
            return;
        }

        numColumns = m * (m - 1) / 2 + 1;
        mems2col = new int[m][m];
        col2mems = new int[numColumns][2];

        for (int x = 0; x < m * (m - 1) / 2 + 1; x++) {
            final int d1 = ((m % 2) == 1) ? 0 : 2 * ((x - 1) / (m - 1)); // amount to add to x to skip
            // columns
            col2mems[x][0] =
                    HashgraphGuiUtils.col2mem(m, d1 + x); // find even m answer by asking for m+1 with skipped cols
            col2mems[x][1] = (((m % 2) == 1) || ((x % (m - 1)) > 0) || (x == 0) || (x == m * (m - 1) / 2))
                    ? -1
                    : HashgraphGuiUtils.col2mem(m, d1 + x + 2);
            final int d = ((m % 2) == 1) ? 0 : 2 * (x / (m - 1)); // amount to add to x to skip columns
            final int a = HashgraphGuiUtils.col2mem(m, d + x);
            final int b = HashgraphGuiUtils.col2mem(m, d + x + 1);
            if (x < m * (m - 1) / 2) { // on the last iteration, x+1 is invalid, so don't record it
                mems2col[b][a] = x;
                mems2col[a][b] = x + 1;
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
     * find the column for e2 next to the column for e1
     */
    public int mems2col(@Nullable final EventImpl e1, @NonNull final EventImpl e2) {
        Objects.requireNonNull(e2, "e2 must not be null");
        // To support Noncontiguous NodeId in the roster,
        // the mems2col array is now based on indexes of NodeIds in the roster.
        final int e2Index = RosterUtils.getIndex(roster, e2.getCreatorId().id());
        if (e1 != null) {
            final int e1Index = RosterUtils.getIndex(roster, e1.getCreatorId().id());
            return mems2col[e1Index][e2Index];
        }
        // there is no e1, so pick one of the e2 columns arbitrarily (next to 0 or 1). If there is only 1
        // member, avoid the array out of bounds exception
        return mems2col[e2Index == 0 ? getNumColumns() == 1 ? 0 : 1 : 0][e2Index];
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
