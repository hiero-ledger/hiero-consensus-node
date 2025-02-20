// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.swirlds.platform.Utilities;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Sorts consensus events into their consensus order */
public class ConsensusSorter implements Comparator<EventImpl> {
    /** an XOR of the signatures of unique famous witnesses in a round, used during sorting */
    final byte[] whitening;

    /**
     * @param whitening an XOR of the signatures of unique famous witnesses in a round
     */
    public ConsensusSorter(@NonNull final byte[] whitening) {
        this.whitening = whitening;
    }

    /**
     * consensus order is to sort by roundReceived, then consensusTimestamp, then generation, then
     * whitened signature.
     */
    @Override
    public int compare(@NonNull final EventImpl e1, @NonNull final EventImpl e2) {
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
        c = Long.compare(e1.getGeneration(), e2.getGeneration());
        if (c != 0) {
            return c;
        }

        // subsort ties by whitened signature
        return Utilities.arrayCompare(
                e1.getBaseEvent().getSignature(), e2.getBaseEvent().getSignature(), whitening);
    }
}
