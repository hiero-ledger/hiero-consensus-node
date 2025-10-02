// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * A validator that ensures that the ancient threshold increases between rounds from the same node.
 */
public enum RoundAncientThresholdIncreasesValidation implements ConsensusRoundConsistencyValidation {
    INSTANCE;

    /**
     * Validates that the threshold info of consecutive rounds for the same node are increasing.
     *
     * @param rounds the rounds produced by a node
     */
    @Override
    public void validate(@NonNull final List<ConsensusRound> rounds) {
        if (rounds.isEmpty()) {
            // An empty list is considered valid
            return;
        }

        for (int i = 1; i < rounds.size(); i++) {

            final MinimumJudgeInfo previousThresholdInfo =
                    rounds.get(i - 1).getSnapshot().minimumJudgeInfoList().getLast();
            final MinimumJudgeInfo currentThresholdInfo =
                    rounds.get(i).getSnapshot().minimumJudgeInfoList().getLast();

            assertThat(currentThresholdInfo.minimumJudgeBirthRound())
                    .withFailMessage("the ancient threshold should never decrease")
                    .isGreaterThanOrEqualTo(previousThresholdInfo.minimumJudgeBirthRound());
        }
    }
}
