// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.framework.validation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.ConsensusOutput;

/**
 * Validate that a given number of rounds has reached consensus
 * @param numberOfRounds the number of rounds to validate
 */
public record NumberOfConsensusRoundsValidation(int numberOfRounds) implements ConsensusOutputValidation {
    @Override
    public void validate(@NonNull final ConsensusOutput output1, @NonNull final ConsensusOutput output2) {
        for (final ConsensusOutput output : List.of(output1, output2)) {
            final int actualRounds = output.getConsensusRounds().size();
            Assertions.assertThat(actualRounds)
                    .withFailMessage(
                            () -> String.format("Expected %d rounds, but got %d", numberOfRounds, actualRounds))
                    .isEqualTo(numberOfRounds);
        }
    }
}
