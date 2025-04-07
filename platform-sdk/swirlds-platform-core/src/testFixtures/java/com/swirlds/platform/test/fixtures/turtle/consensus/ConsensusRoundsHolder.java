// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.consensus;

import edu.umd.cs.findbugs.annotations.NonNull;
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
    void interceptRounds(@NonNull final List<ConsensusRound> rounds);

    /**
     * Clear the specified consensus rounds from the collection.
     *
     * @param roundNumbers the round numbers to clear
     */
    void clear(@NonNull final Set<Long> roundNumbers);

    /**
     * Get the collected consensus rounds in a Map linking round number with its corresponding round.
     *
     * @return the collected consensus rounds
     */
    Map<Long, ConsensusRound> getCollectedRounds();

    /**
     * Get filtered consensus rounds by specified consensus round numbers.
     *
     * @param roundNums the consensus round numbers collection to use as a filter
     * @return the filtered consensus rounds
     */
    List<ConsensusRound> getFilteredConsensusRounds(@NonNull final Set<Long> roundNums);
}
