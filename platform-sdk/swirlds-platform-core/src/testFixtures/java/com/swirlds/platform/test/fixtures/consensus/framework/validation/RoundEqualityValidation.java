// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.platform.internal.ConsensusRound;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates that the consensus rounds are the same.
 */
public class RoundEqualityValidation implements ConsensusRoundValidation {

    private final RoundInternalEqualityValidation roundInternalEqualityValidation =
            new RoundInternalEqualityValidation();
    private final RoundAncientThresholdIncreasesValidation roundAncientThresholdIncreasesValidation =
            new RoundAncientThresholdIncreasesValidation();

    /**
     * Validates the rounds from two different sources including the internal round information.
     *
     * @param output1 the first source of rounds
     * @param output2 the second source of rounds
     */
    @Override
    public void validate(@NonNull List<ConsensusRound> output1, @NonNull List<ConsensusRound> output2) {
        roundInternalEqualityValidation.validate(output1, output2);

        assertEquals(
                output1.size(),
                output2.size(),
                String.format(
                        "The number of consensus rounds is not the same."
                                + "output1 has %d rounds, output2 has %d rounds",
                        output1.size(), output2.size()));
        roundAncientThresholdIncreasesValidation.validate(output1, new ArrayList<>());
    }
}
