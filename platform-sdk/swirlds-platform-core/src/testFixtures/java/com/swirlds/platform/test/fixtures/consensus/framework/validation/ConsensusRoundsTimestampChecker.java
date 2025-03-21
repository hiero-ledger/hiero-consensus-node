// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.internal.ConsensusRound;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.junit.jupiter.api.Assertions;

/**
 * Validates that the timestamps in consensus rounds are correct.
 */
public class ConsensusRoundsTimestampChecker implements ConsensusRoundValidation {

    /**
     * Validate the timestamps in consensus rounds are properly increasing.
     * */
    @Override
    public void validate(@NonNull final List<ConsensusRound> output1, @NonNull final List<ConsensusRound> ignored) {
        PlatformEvent previousConsensusEvent = null;

        for (final ConsensusRound round : output1) {
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
}
