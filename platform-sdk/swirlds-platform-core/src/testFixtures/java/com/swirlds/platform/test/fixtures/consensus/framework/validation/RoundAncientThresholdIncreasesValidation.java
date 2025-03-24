// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.swirlds.platform.test.fixtures.consensus.framework.validation.ConsensusRoundValidator.ConsensusRoundsNodeOrigin;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

public class RoundAncientThresholdIncreasesValidation implements ConsensusRoundValidation {

    public ConsensusRoundsNodeOrigin getNodeOrigin() {
        return ConsensusRoundsNodeOrigin.SAME;
    }

    /**
     * Validates that the threshold info of consequent rounds for the same node are increasing.
     *
     * @param round1 a given node's round be validated
     * @param round2 the consequent round from the same node to be validated
     */
    @Override
    public void validate(@NonNull final ConsensusRound round1, @NonNull final ConsensusRound round2) {
        final MinimumJudgeInfo thresholdInfoForFirstRound =
                round1.getSnapshot().minimumJudgeInfoList().getLast();
        final MinimumJudgeInfo thresholdInfoForSecondRound =
                round2.getSnapshot().minimumJudgeInfoList().getLast();
        assertThat(round1.getRoundNum())
                .isEqualTo(thresholdInfoForFirstRound.round())
                .withFailMessage("the last threshold should be for the current round");
        assertThat(round2.getRoundNum())
                .isEqualTo(thresholdInfoForSecondRound.round())
                .withFailMessage("the last threshold should be for the current round");
        assertThat(thresholdInfoForFirstRound.minimumJudgeAncientThreshold())
                .isLessThanOrEqualTo(thresholdInfoForSecondRound.minimumJudgeAncientThreshold())
                .withFailMessage("the ancient threshold should never decrease");
    }
}
