// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.consensus;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * A container for collecting list of consensus rounds produced by the ConsensusEngine using List.
 */
public class DefaultConsensusRoundsTestCollector implements ConsensusRoundsTestCollector {

    final List<ConsensusRound> collectedRounds = new ArrayList<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public void interceptRounds(final List<ConsensusRound> rounds) {
        collectedRounds.addAll(rounds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        collectedRounds.clear();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    public List<ConsensusRound> getCollectedRounds() {
        return collectedRounds;
    }
}
