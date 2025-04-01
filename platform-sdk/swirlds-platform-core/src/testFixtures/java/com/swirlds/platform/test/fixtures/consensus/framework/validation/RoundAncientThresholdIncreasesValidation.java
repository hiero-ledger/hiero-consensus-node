// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * A validator that ensures that the ancient threshold increases between rounds from the same node.
 */
public class RoundAncientThresholdIncreasesValidation implements ConsensusRoundValidation {

    /**
     * Validates that the threshold info of consequent rounds for the same node are increasing.
     *
     * @param round1 a given node's round be validated
     * @param round2 the consequent round from the same node to be validated
     */
    @Override
    public void validate(@NonNull final ConsensusRound round1, @NonNull final ConsensusRound round2) {
        final MinimumJudgeInfo thresholdInfoForRound1 =
                round1.getSnapshot().minimumJudgeInfoList().getLast();
        final MinimumJudgeInfo thresholdInfoForRound2 =
                round2.getSnapshot().minimumJudgeInfoList().getLast();
        assertThat(round1.getRoundNum())
                .withFailMessage(String.format(
                        "the last threshold should be equal for the current round %d", round1.getRoundNum()))
                .isEqualTo(thresholdInfoForRound1.round());
        assertThat(round2.getRoundNum())
                .withFailMessage(String.format(
                        "the last threshold should be equal for the current round %d", round2.getRoundNum()))
                .isEqualTo(thresholdInfoForRound2.round());
        assertThat(thresholdInfoForRound2.minimumJudgeAncientThreshold())
                .withFailMessage("the ancient threshold should never decrease")
                .isGreaterThanOrEqualTo(thresholdInfoForRound1.minimumJudgeAncientThreshold());
    }
}
