// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Validates that the timestamps of consensus events increase.
 */
public enum RoundTimestampCheckerValidation implements ConsensusRoundConsistencyValidation {
    INSTANCE;

    /**
     * Validate the timestamps of {@link PlatformEvent} in a consensus round are increasing.
     *
     * @param rounds the rounds to validate
     * @throws AssertionError if the validation fails
     */
    @Override
    public void validate(@NonNull final List<ConsensusRound> rounds) {
        for (final ConsensusRound round : rounds) {
            for (int i = 1; i < round.getEventCount(); i++) {

                final PlatformEvent previousEvent = round.getConsensusEvents().get(i - 1);
                final PlatformEvent currentEvent = round.getConsensusEvents().get(i);

                assertThat(currentEvent.getConsensusTimestamp())
                        .withFailMessage(String.format(
                                "Consensus time does not increase!%n"
                                        + "Event %s consOrder:%s consTime:%s%n"
                                        + "Event %s consOrder:%s consTime:%s%n",
                                previousEvent.getDescriptor(),
                                previousEvent.getConsensusOrder(),
                                previousEvent.getConsensusTimestamp(),
                                currentEvent.getDescriptor(),
                                currentEvent.getConsensusOrder(),
                                currentEvent.getConsensusTimestamp()))
                        .isAfter(previousEvent.getConsensusTimestamp());
            }
        }
    }
}
