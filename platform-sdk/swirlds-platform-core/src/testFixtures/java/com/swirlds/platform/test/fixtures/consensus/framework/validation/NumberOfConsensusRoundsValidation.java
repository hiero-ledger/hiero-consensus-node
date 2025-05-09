// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.assertj.core.api.Assertions;

/**
 * Validate that a given number of rounds has reached consensus
 * @param numberOfRounds the number of rounds to validate
 */
public record NumberOfConsensusRoundsValidation(int numberOfRounds) implements ConsensusOutputValidation {
    @Override
    public void validate(@NonNull final ConsensusOutput output1, @NonNull final ConsensusOutput output2) {
        for (final ConsensusOutput output : List.of(output1, output2)) {
            Assertions.assertThat(output.getConsensusRounds().size())
                    .withFailMessage(
                            "Expected %d rounds, but got %d",
                            numberOfRounds, output.getConsensusRounds().size())
                    .isEqualTo(numberOfRounds);
        }
    }
}
