// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.consensus;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * A test component collecting consensus rounds produced by the ConsensusEngine
 */
public interface ConsensusRoundsHolder {

    /**
     * Intercept the consensus rounds produced by the ConsensusEngine and adds them to a collection.
     *
     * @param rounds
     */
    void interceptRounds(final List<ConsensusRound> rounds);

    /**
     * Clear the specified consensus rounds from the collection.
     *
     * @param roundNumbers the round numbers to clear
     */
    void clear(final Set<Long> roundNumbers);

    /**
     * Get the collected consensus rounds in a Map linking round number with its corresponding round.
     *
     * @return the collected consensus rounds
     */
    Map<Long, ConsensusRound> getCollectedRounds();
}
