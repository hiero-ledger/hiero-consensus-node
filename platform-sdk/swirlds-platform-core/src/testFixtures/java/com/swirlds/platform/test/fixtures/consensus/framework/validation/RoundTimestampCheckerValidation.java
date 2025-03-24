// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.internal.ConsensusRound;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Validates that the timestamps in consensus rounds are increasing in proper manner.
 */
public class RoundTimestampCheckerValidation implements ConsensusRoundValidation {

    /**
     * Validate the timestamps in consensus rounds.
     *
     * @param round1 the round to validate
     */
    @Override
    public void validate(@NonNull final ConsensusRound round1, @NonNull final ConsensusRound ignoredRound) {
        PlatformEvent previousConsensusEvent = null;

        for (final PlatformEvent e : round1.getConsensusEvents()) {
            if (previousConsensusEvent == null) {
                previousConsensusEvent = e;
                continue;
            }
            assertThat(e.getConsensusTimestamp()).isNotNull();
            assertThat(previousConsensusEvent.getConsensusTimestamp()).isNotNull();
            assertThat(e.getConsensusTimestamp().isAfter(previousConsensusEvent.getConsensusTimestamp()))
                    .isTrue()
                    .withFailMessage(String.format(
                            "Consensus time does not increase!%n"
                                    + "Event %s consOrder:%s consTime:%s%n"
                                    + "Event %s consOrder:%s consTime:%s%n",
                            previousConsensusEvent.getDescriptor(),
                            previousConsensusEvent.getConsensusOrder(),
                            previousConsensusEvent.getConsensusTimestamp(),
                            e.getDescriptor(),
                            e.getConsensusOrder(),
                            e.getConsensusTimestamp()));
            previousConsensusEvent = e;
        }
    }
}
