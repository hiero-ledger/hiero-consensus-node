// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.hashgraph.internal;

import com.swirlds.platform.gui.hashgraph.HashgraphGuiConstants;
import com.swirlds.platform.gui.hashgraph.HashgraphPictureOptions;
import com.swirlds.platform.internal.EventImpl;
import java.awt.Color;

/**
 * Various static utils for the {@link com.swirlds.platform.gui.hashgraph.HashgraphGui}
 */
public final class HashgraphGuiUtils {
    private HashgraphGuiUtils() {}

    /**
     * return the member number for column x, if there are m members. This is set up so each member appears
     * in multiple columns, and for any two members there will be exactly one location where they have
     * adjacent columns.
     * <p>
     * The pattern used comes from a Eulerian cycle on the complete graph of m members, formed by combining
     * floor(m/2) disjoint Eulerian paths on the complete graph of m-1 members.
     * <p>
     * This method assumes an odd number of members. If there are an even number of members, then assume
     * there is one extra member, use this method, then delete the columns of the fictitious member, and
     * combine those columns on either side of each deleted one.
     *
     * @param m
     * 		the number of members (must be odd)
     * @param x
     * 		the column (from 0 to 1+m*(m-1)/2)
     * @return the member number (from 0 to m-1)
     */
    public static int col2mem(int m, final int x) {
        m = (m / 2) * 2 + 1; // if m is even, round up to the nearest odd
        final int i = (x / m) % (m / 2); // the ith Eulerian path on the complete graph of m-1 vertices
        final int j = x % m; // position along that ith path

        if (j == m - 1) {
            return m - 1; // add the mth vertex after each Eulerian path to get a Eulerian cycle
        }

        if ((j % 2) == 0) {
            return i + j / 2; // in a given path, every other vertex counts up
        }

        return (m - 2 + i - (j - 1) / 2) % (m - 1); // and every other vertex counts down
    }

    /**
     * Return the color for an event based on calculations in the consensus algorithm A non-witness is gray,
     * and a witness has a color of green (famous), blue (not famous) or red (undecided fame). When the
     * event becomes part of the consensus, its color becomes darker.
     *
     * @param event
     * 		the event to color
     * @return its color
     */
    public static Color eventColor(final EventImpl event, final HashgraphPictureOptions options) {
        if (options.simpleColors()) { // if checkbox checked
            return event.isConsensus() ? HashgraphGuiConstants.LIGHT_BLUE : HashgraphGuiConstants.LIGHT_GREEN;
        }
        if (!event.isWitness()) {
            return event.isConsensus() ? HashgraphGuiConstants.DARK_GRAY : HashgraphGuiConstants.LIGHT_GRAY;
        }
        // after this point, we know the event is a witness
        if (!event.isFameDecided()) {
            return event.isConsensus() ? HashgraphGuiConstants.DARK_RED : HashgraphGuiConstants.LIGHT_RED;
        }
        // after this point, we know the event is a witness and fame is decided
        if (event.isJudge()) {
            return event.isConsensus() ? HashgraphGuiConstants.DARK_BLUE : HashgraphGuiConstants.LIGHT_BLUE;
        }
        if (event.isFamous()) {
            return event.isConsensus() ? HashgraphGuiConstants.DARK_GREEN : HashgraphGuiConstants.LIGHT_GREEN;
        }

        // if we reached here, it means the event is a witness, fame is decided, but it is not famous
        return event.isConsensus() ? HashgraphGuiConstants.DARK_YELLOW : HashgraphGuiConstants.LIGHT_YELLOW;
    }
}
