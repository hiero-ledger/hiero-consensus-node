// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Validates rounds produced by a test. The type of validation that is done depends on the implementation.
 */
@FunctionalInterface
public interface ConsensusRoundComparisonValidation {

    /**
     * Perform validation on the passed consensus rounds.
     *
     * @param round1 the round from one node
     * @param round2 the round from another node
     * @throws AssertionError if the validation fails
     */
    void validate(@NonNull final ConsensusRound round1, @NonNull final ConsensusRound round2);
}
