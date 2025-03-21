// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.swirlds.platform.internal.ConsensusRound;
import com.swirlds.platform.system.events.EventConstants;
import edu.umd.cs.findbugs.annotations.NonNull;

public class RoundAncientThresholdIncreasesValidation implements ConsensusRoundValidation {

    /**
     * Validates that the threshold info of the rounds in the list is increasing for each next round
     *
     * @param firstRound to validate
     */
    @Override
    public void validate(@NonNull final ConsensusRound firstRound, @NonNull final ConsensusRound ignoredRound) {
        long lastAncientThreshold = EventConstants.ANCIENT_THRESHOLD_UNDEFINED;
        final MinimumJudgeInfo thresholdInfo =
                firstRound.getSnapshot().minimumJudgeInfoList().getLast();
        assertThat(firstRound.getRoundNum())
                .isEqualTo(thresholdInfo.round())
                .withFailMessage(() -> "the last threshold should be for the current round");
        if (EventConstants.ANCIENT_THRESHOLD_UNDEFINED != thresholdInfo.minimumJudgeAncientThreshold())
            lastAncientThreshold = thresholdInfo.minimumJudgeAncientThreshold();
        assertThat(thresholdInfo.minimumJudgeAncientThreshold())
                .isGreaterThanOrEqualTo(lastAncientThreshold)
                .withFailMessage(() -> "the ancient threshold should never decrease");
    }
}
