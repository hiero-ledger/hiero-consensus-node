// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Validates rounds produced by a single node during a test. The type of validation that is done depends on the implementation.
 */
@FunctionalInterface
public interface ConsensusRoundConsistencyValidation {

    /**
     * Perform validation on the passed consensus rounds.
     *
     * <p>An empty list is considered valid.
     *
     * @param rounds the rounds to validate
     * @throws AssertionError if the validation fails
     */
    void validate(@NonNull final List<ConsensusRound> rounds);
}
