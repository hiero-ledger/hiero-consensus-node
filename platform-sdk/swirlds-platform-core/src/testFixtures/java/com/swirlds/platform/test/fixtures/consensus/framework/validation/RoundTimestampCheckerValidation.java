// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.internal.ConsensusRound;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.Assertions;

/**
 * Validates that the timestamps in consensus rounds are correct.
 */
public class RoundTimestampCheckerValidation implements ConsensusRoundValidation {

    /**
     * Validate the timestamps in consensus rounds are properly increasing.
     *
     * @param round to validate
     */
    @Override
    public void validate(@NonNull final ConsensusRound round, @NonNull final ConsensusRound ignoredRound) {
        PlatformEvent previousConsensusEvent = null;

        for (final PlatformEvent e : round.getConsensusEvents()) {
            if (previousConsensusEvent == null) {
                previousConsensusEvent = e;
                continue;
            }
            Assertions.assertNotNull(e.getConsensusTimestamp());
            Assertions.assertNotNull(previousConsensusEvent.getConsensusTimestamp());
            Assertions.assertTrue(
                    e.getConsensusTimestamp().isAfter(previousConsensusEvent.getConsensusTimestamp()),
                    String.format(
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
