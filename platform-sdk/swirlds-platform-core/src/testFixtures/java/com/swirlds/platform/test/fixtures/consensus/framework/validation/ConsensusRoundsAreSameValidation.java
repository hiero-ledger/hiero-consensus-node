// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static com.swirlds.platform.test.fixtures.consensus.framework.validation.RoundContentValidation.validateAncientThresholdIncreases;
import static com.swirlds.platform.test.fixtures.consensus.framework.validation.RoundContentValidation.validateIterableRounds;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.platform.internal.ConsensusRound;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Validates that the consensus rounds are the same.
 */
public class ConsensusRoundsAreSameValidation implements ConsensusRoundValidation {

    /**
     * Validates the rounds from two different sources including the internal round information.
     *
     * @param output1 the first source of rounds
     * @param output2 the second source of rounds
     */
    @Override
    public void validate(@NonNull List<ConsensusRound> output1, @NonNull List<ConsensusRound> output2) {
        validateIterableRounds(output1.iterator(), output2.iterator());

        assertEquals(
                output1.size(),
                output2.size(),
                String.format(
                        "The number of consensus rounds is not the same."
                                + "output1 has %d rounds, output2 has %d rounds",
                        output1.size(), output2.size()));
        validateAncientThresholdIncreases(output1);
    }
}
