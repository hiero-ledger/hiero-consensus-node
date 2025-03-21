// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.internal.ConsensusRound;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Validates rounds produced by a test. The type of validation that is done depends on the implementation.
 */
@FunctionalInterface
public interface ConsensusRoundValidation {

    /**
     * Perform validation on all consensus rounds.
     *
     * @param firstRoundsList the rounds from one node
     * @param secondRoundsList the rounds from another node
     */
    void validate(
            @NonNull final List<ConsensusRound> firstRoundsList, @NonNull final List<ConsensusRound> secondRoundsList);
}
