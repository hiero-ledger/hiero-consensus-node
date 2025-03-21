// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.swirlds.platform.internal.ConsensusRound;
import com.swirlds.platform.system.events.EventConstants;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

public class RoundAncientThresholdIncreasesValidation implements ConsensusRoundValidation {

    /**
     * Validates that the threshold info of the rounds in the list is increasing for each next round
     *
     * @param output1 to validate
     */
    @Override
    public void validate(@NonNull List<ConsensusRound> output1, @NonNull List<ConsensusRound> output2) {
        long lastAncientThreshold = EventConstants.ANCIENT_THRESHOLD_UNDEFINED;
        for (final ConsensusRound round : output1) {
            final MinimumJudgeInfo thresholdInfo =
                    round.getSnapshot().minimumJudgeInfoList().getLast();
            assertEquals(
                    round.getRoundNum(), thresholdInfo.round(), "the last threshold should be for the current round");
            assertTrue(
                    thresholdInfo.minimumJudgeAncientThreshold() >= lastAncientThreshold,
                    "the ancient threshold should never decrease");
            lastAncientThreshold = thresholdInfo.minimumJudgeAncientThreshold();
        }
    }
}
