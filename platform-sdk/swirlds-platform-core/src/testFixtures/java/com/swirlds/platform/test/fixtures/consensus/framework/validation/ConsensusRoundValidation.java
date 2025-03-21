// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.internal.ConsensusRound;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Validates rounds produced by a test. The type of validation that is done depends on the implementation.
 */
@FunctionalInterface
public interface ConsensusRoundValidation {

    /**
     * Perform validation on the passed consensus rounds.
     *
     * @param firstRound the round from one node
     * @param secondRound the round from another node
     */
    void validate(@NonNull final ConsensusRound firstRound, @NonNull final ConsensusRound secondRound);
}
