// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * A validator that checks the internal consistency of all rounds in a list.
 */
public enum RoundInternalConsistencyValidation implements ConsensusRoundConsistencyValidation {
    INSTANCE;

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate(@NonNull final List<ConsensusRound> rounds) {
        for (final ConsensusRound round : rounds) {
            final MinimumJudgeInfo minimumJudgeInfo =
                    round.getSnapshot().minimumJudgeInfoList().getLast();
            assertThat(round.getRoundNum())
                    .withFailMessage(String.format(
                            "the last threshold should be equal for the current round %d", round.getRoundNum()))
                    .isEqualTo(minimumJudgeInfo.round());

            for (final PlatformEvent event : round.getConsensusEvents()) {
                assertThat(event.getConsensusData()).isNotNull();
            }
        }
    }
}
