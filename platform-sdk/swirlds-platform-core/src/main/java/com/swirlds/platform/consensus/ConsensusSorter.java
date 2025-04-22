// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.swirlds.platform.Utilities;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;

/** Sorts consensus events into their consensus order */
public class ConsensusSorter {
    /** an XOR of the hashes of unique famous witnesses in a round, used during sorting */
    private final byte[] whitening;

    /**
     * @param whitening an XOR of the hashes of unique famous witnesses in a round
     */
    private ConsensusSorter(@NonNull final byte[] whitening) {
        this.whitening = whitening;
    }

    /**
     * Sorts the events into consensus order. The events are sorted by their consensus timestamp, then by their
     * extended median timestamp, then by their generation, and finally by their whitened signature.
     *
     * @param events the list of events to sort
     * @param whitening an XOR of the hashes of unique famous witnesses in a round
     */
    public static void sort(@NonNull final List<EventImpl> events, @NonNull final byte[] whitening) {
        // assign cGen to the events, which is needed for sorting
        LocalConsensusGeneration.assignCGen(events);
        // sort the events into consensus order
        events.sort(new ConsensusSorter(whitening)::compare);
        // clear cGen from the events, which is no longer needed
        LocalConsensusGeneration.clearCGen(events);
    }

    /**
     * consensus order is to sort by roundReceived, then consensusTimestamp, then generation, then
     * whitened signature.
     */
    private int compare(@NonNull final EventImpl e1, @NonNull final EventImpl e2) {
        int c;

        // sort by consensus timestamp
        c = (e1.getPreliminaryConsensusTimestamp().compareTo(e2.getPreliminaryConsensusTimestamp()));
        if (c != 0) {
            return c;
        }

        // subsort ties by extended median timestamp
        final List<Instant> recTimes1 = e1.getRecTimes();
        final List<Instant> recTimes2 = e2.getRecTimes();

        final int m1 = recTimes1.size() / 2; // middle position of e1 (the later of the two middles, if even length)
        final int m2 = recTimes2.size() / 2; // middle position of e2 (the later of the two middles, if even length)
        int d = -1; // offset from median position to look at
        while (m1 + d >= 0 && m2 + d >= 0 && m1 + d < recTimes1.size() && m2 + d < recTimes2.size()) {
            c = recTimes1.get(m1 + d).compareTo(recTimes2.get(m2 + d));
            if (c != 0) {
                return c;
            }
            d = d < 0 ? -d : (-d - 1); // use the median position plus -1, 1, -2, 2, -3, 3, ...
        }

        // subsort ties by generation
        c = Long.compare(e1.getCGen(), e2.getCGen());
        if (c != 0) {
            return c;
        }

        // subsort ties by whitened hashes
        return Utilities.arrayCompare(
                e1.getBaseHash().getBytes(), e2.getBaseHash().getBytes(), whitening);
    }
}
