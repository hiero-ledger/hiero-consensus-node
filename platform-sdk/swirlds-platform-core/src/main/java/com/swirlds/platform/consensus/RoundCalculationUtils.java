// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.NoSuchElementException;
import org.hiero.consensus.model.event.EventConstants;

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
     * @param roundsNonAncient the number of non-ancient rounds
     * @return minimum non-ancient generation
     */
    public static long getAncientThreshold(final int roundsNonAncient, @NonNull final ConsensusSnapshot snapshot) {
        final long oldestNonAncientRound =
                RoundCalculationUtils.getOldestNonAncientRound(roundsNonAncient, snapshot.round());
        return getMinimumJudgeAncientThreshold(oldestNonAncientRound, snapshot);
    }

    /**
     * The minimum ancient threshold of famous witnesses (i.e. judges) for the round specified. This method only looks
     * at non-ancient rounds contained within this state.
     *
     * @param round the round whose minimum judge ancient indicator will be returned
     * @return the minimum judge ancient indicator for the round specified
     * @throws NoSuchElementException if the minimum judge info information for this round is not contained withing this
     *                                state
     */
    public static long getMinimumJudgeAncientThreshold(final long round, @NonNull final ConsensusSnapshot snapshot) {
        for (final MinimumJudgeInfo info : snapshot.minimumJudgeInfoList()) {
            if (info.round() == round) {
                return info.minimumJudgeBirthRound();
            }
        }
        throw new NoSuchElementException("No minimum judge info found for round: " + round);
    }
}
