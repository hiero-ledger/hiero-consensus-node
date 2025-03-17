// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.consensus.framework.validation;

import com.swirlds.platform.internal.ConsensusRound;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Validates rounds of a test. The type of validation that is done depends on the implementation.
 */
public interface ConsensusRoundValidation {

    /**
     * Perform validation on all consensus rounds.
     *
     * @param output1 the rounds from one node
     * @param output2 the rounds from another node
     */
    void validate(@NonNull final List<ConsensusRound> output1, @NonNull final List<ConsensusRound> output2);
}
