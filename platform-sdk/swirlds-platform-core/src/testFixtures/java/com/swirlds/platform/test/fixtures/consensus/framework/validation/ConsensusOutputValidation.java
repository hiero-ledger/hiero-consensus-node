// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Validates output of a consensus test. The type of validation that is done depends on the implementation.
 */
@FunctionalInterface
public interface ConsensusOutputValidation {
    /**
     * Perform validation on all consensus output.
     *
     * @param output1 the output from one node
     * @param output2 the output from another node
     */
    void validate(@NonNull final ConsensusOutput output1, @NonNull final ConsensusOutput output2);
}
