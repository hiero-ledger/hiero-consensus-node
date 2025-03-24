// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.consensus;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
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
     * Clear the internal state of this collector.
     *
     */
    void clear();

    /**
     * Get the collected consensus rounds.
     *
     * @return the collected consensus rounds
     */
    List<ConsensusRound> getCollectedRounds();
}
