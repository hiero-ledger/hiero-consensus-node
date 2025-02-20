// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.consensus.framework.validation;

import com.swirlds.platform.internal.ConsensusRound;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

public interface ConsensusRoundValidation {
    /**
     * Perform validation on consensus rounds.
     *
     * @param consensusRoundsForNode1 the consensus rounds from one node
     * @param consensusRoundsForNode2 the consensus rounds from another node
     */
    void validate(
            @NonNull final List<ConsensusRound> consensusRoundsForNode1,
            @NonNull final List<ConsensusRound> consensusRoundsForNode2);
}
