// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.swirlds.platform.system.events.EventConstants;
import java.util.function.LongUnaryOperator;

/**
 * Utilities for calculating round numbers
 */
public final class RoundCalculationUtils {
    private RoundCalculationUtils() {}

    /**
     * Returns the oldest round that is non-ancient. If no round is ancient, then it will return the first round ever
     *
     * @param roundsNonAncient
     * 		the number of non-ancient rounds
     * @param lastRoundDecided
     * 		the last round that has fame decided
     * @return oldest non-ancient number
     */
    public static long getOldestNonAncientRound(final int roundsNonAncient, final long lastRoundDecided) {
        // if we have N non-ancient consensus rounds, and the last one is M, then the oldest non-ancient round is
        // M-(N-1) which is equal to M-N+1
        // if no rounds are ancient yet, then the oldest non-ancient round is the first round ever
        return Math.max(lastRoundDecided - roundsNonAncient + 1, EventConstants.MINIMUM_ROUND_CREATED);
    }

    /**
     * Returns the minimum generation below which all events are ancient
     *
     * @param roundsNonAncient
     * 		the number of non-ancient rounds
     * @param lastRoundDecided
     * 		the last round that has fame decided
     * @param roundGenerationProvider
     * 		returns a round generation number for a given round number
     * @return minimum non-ancient generation
     */
    public static long getMinGenNonAncient(
            final int roundsNonAncient, final long lastRoundDecided, final LongUnaryOperator roundGenerationProvider) {
        // if a round generation is not defined for the oldest round, it will be EventConstants.GENERATION_UNDEFINED,
        // which is -1. in this case we will return FIRST_GENERATION, which is 0
        return Math.max(
                roundGenerationProvider.applyAsLong(getOldestNonAncientRound(roundsNonAncient, lastRoundDecided)),
                EventConstants.FIRST_GENERATION);
    }
}
