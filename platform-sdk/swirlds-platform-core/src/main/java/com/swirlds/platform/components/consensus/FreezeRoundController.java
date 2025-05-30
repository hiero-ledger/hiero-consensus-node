// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components.consensus;

import com.swirlds.platform.freeze.FreezeCheckHolder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * A class that controls freeze rounds. It filters out rounds if multiple rounds reach consensus when the freeze period
 * is reached, and modifies the {@link org.hiero.consensus.model.hashgraph.EventWindow} of the freeze round.
 */
public class FreezeRoundController {

    /** Checks if consensus time has reached the freeze period */
    private final FreezeCheckHolder freezeChecker;

    /** Indicates if the freeze period has been reached. */
    private boolean isFrozen = false;

    public FreezeRoundController(@NonNull final FreezeCheckHolder freezeChecker) {
        this.freezeChecker = Objects.requireNonNull(freezeChecker);
    }

    /**
     * Checks all rounds to see if any are in the freeze period. If there are multiple rounds in the freeze period, only
     * the first one will be kept and the rest will be removed from the list. It will also modify the EventWindow of the
     * freeze round to change the event birth round.
     *
     * @param consensusRounds the list of consensus rounds to check
     */
    public List<ConsensusRound> filterAndModify(@NonNull final List<ConsensusRound> consensusRounds) {
        // we first check if there is any freeze round. usually there isn't, so we don't start any modifications until
        // we found a freeze round.
        if (consensusRounds.stream().map(ConsensusRound::getConsensusTimestamp).noneMatch(freezeChecker)) {
            // no freeze round found, so we can return the list as is
            return consensusRounds;
        }

        // from now on, we are frozen
        isFrozen = true;

        // if there is a freeze round, we need to modify the list
        final List<ConsensusRound> modifiedRounds = new ArrayList<>();
        for (final ConsensusRound round : consensusRounds) {
            if (freezeChecker.test(round.getConsensusTimestamp())) {
                // if it's the freeze round, we need to modify it and add it to the list
                modifiedRounds.add(modifyFreezeRound(round));
                // we can stop here, since we only want the first freeze round
                return modifiedRounds;
            } else {
                // if it's a round before the freeze round, we can add it to the list as is
                modifiedRounds.add(round);
            }
        }
        return modifiedRounds;
    }

    /**
     * Modifies the freeze round to change the event birth round to the latest consensus round. This is to ensure that
     * events created pre-upgrade and post-upgrade can be distinguished in case some migration logic is needed.
     * @param round the round to modify
     * @return the modified round
     */
    private static ConsensusRound modifyFreezeRound(@NonNull final ConsensusRound round) {
        final EventWindow modifiedWindow = new EventWindow(
                round.getEventWindow().latestConsensusRound(),
                // the event window is modified so that the event birth round is the same as the latest consensus round.
                // this is to ensure that events created pre-upgrade and post-upgrade can be distinguished in case some
                // migration logic is needed.
                round.getEventWindow().latestConsensusRound(),
                round.getEventWindow().ancientThreshold(),
                round.getEventWindow().expiredThreshold(),
                round.getEventWindow().ancientMode());
        return new ConsensusRound(
                round.getConsensusRoster(),
                round.getConsensusEvents(),
                modifiedWindow,
                round.getSnapshot(),
                round.isPcesRound(),
                round.getReachedConsTimestamp());
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
