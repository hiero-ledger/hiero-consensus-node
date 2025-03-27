// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.consensus;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * A container for collecting list of consensus rounds produced by the ConsensusEngine using List.
 */
public class ConsensusRoundsListContainer implements ConsensusRoundsHolder {

    final Map<Long, ConsensusRound> collectedRounds = new TreeMap<>();

    @Override
    public void interceptRounds(final List<ConsensusRound> rounds) {
        for (final ConsensusRound round : rounds) {
            collectedRounds.put(round.getRoundNum(), round);
        }
    }

    @Override
    public void clear(final Set<Long> roundNumbers) {
        for (final Long roundNumber : roundNumbers) {
            collectedRounds.remove(roundNumber);
        }
    }

    @NonNull
    @Override
    public Map<Long, ConsensusRound> getCollectedRounds() {
        return collectedRounds;
    }
}
