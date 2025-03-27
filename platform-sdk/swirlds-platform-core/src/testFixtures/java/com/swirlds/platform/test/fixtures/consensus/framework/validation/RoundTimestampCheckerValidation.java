// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Validates that the timestamps of consensus events increase.
 */
public class RoundTimestampCheckerValidation implements ConsensusRoundValidation {

    /**
     * Validate the timestamps of {@link PlatformEvent} in a consensus round are increasing.
     *
     * @param round1 the round to validate from one node
     * @param round2 the round to validate from another node
     */
    @Override
    public void validate(@NonNull final ConsensusRound round1, @NonNull final ConsensusRound round2) {
        validateSingleRound(round1);
        validateSingleRound(round2);
    }

    private void validateSingleRound(final ConsensusRound round) {
        PlatformEvent previousConsensusEvent = null;

        for (final PlatformEvent e : round.getConsensusEvents()) {
            if (previousConsensusEvent == null) {
                previousConsensusEvent = e;
                continue;
            }
            assertThat(e.getConsensusTimestamp()).isNotNull();
            assertThat(previousConsensusEvent.getConsensusTimestamp()).isNotNull();
            assertThat(e.getConsensusTimestamp().isAfter(previousConsensusEvent.getConsensusTimestamp()))
                    .withFailMessage(String.format(
                            "Consensus time does not increase!%n"
                                    + "Event %s consOrder:%s consTime:%s%n"
                                    + "Event %s consOrder:%s consTime:%s%n",
                            previousConsensusEvent.getDescriptor(),
                            previousConsensusEvent.getConsensusOrder(),
                            previousConsensusEvent.getConsensusTimestamp(),
                            e.getDescriptor(),
                            e.getConsensusOrder(),
                            e.getConsensusTimestamp()))
                    .isTrue();
            previousConsensusEvent = e;
        }
    }
}
