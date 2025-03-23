// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.consensus;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * A container for collecting list of consensus rounds produced by the ConsensusEngine using List.
 */
public class ConsensusRoundsListContainer implements ConsensusRoundsHolder {

    final List<ConsensusRound> collectedRounds = new ArrayList<>();

    @Override
    public void interceptRounds(final List<ConsensusRound> rounds) {
        if (!rounds.isEmpty()) {
            collectedRounds.addAll(rounds);
        }
    }

    @Override
    public void clear(@NonNull final Object ignored) {
        collectedRounds.clear();
    }
}
