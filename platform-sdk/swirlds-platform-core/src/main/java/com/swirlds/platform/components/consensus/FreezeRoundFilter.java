// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components.consensus;

import com.swirlds.platform.freeze.FreezeCheckHolder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * A class that filters out rounds if multiple rounds reach consensus when the freeze period is reached. Only the first
 * round will be returned. All round after the first freeze round are removed.
 */
public class FreezeRoundFilter {

    /** Checks if consensus time has reached the freeze period */
    private final FreezeCheckHolder freezeChecker;

    /** Indicates if the freeze period has been reached. */
    private boolean isFrozen = false;

    public FreezeRoundFilter(@NonNull final FreezeCheckHolder freezeChecker) {
        this.freezeChecker = Objects.requireNonNull(freezeChecker);
    }

    /**
     * Checks all rounds to see if any are in the freeze period. If there are multiple rounds in the freeze period, only
     * the first one will be kept and the rest will be removed from the list.
     *
     * @param consensusRounds the list of consensus rounds to check
     * @return true if any rounds are in the freeze period, false otherwise
     */
    public boolean filter(@NonNull final List<ConsensusRound> consensusRounds) {
        boolean freezeRoundFound = false;
        final Iterator<ConsensusRound> iterator = consensusRounds.iterator();
        while (iterator.hasNext()) {
            final ConsensusRound round = iterator.next();
            if (freezeRoundFound) {
                iterator.remove();
                continue;
            }
            if (freezeChecker.test(round.getConsensusTimestamp())) {
                freezeRoundFound = true;
                isFrozen = true;
            }
        }
        return freezeRoundFound;
    }

    /**
     * Returns true if the freeze period has been reached.
     *
     * @return true if the freeze period has been reached, false otherwise
     */
    public boolean isFrozen() {
        return isFrozen;
    }
}
