// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.staking;

import static com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUtils.computeExtendedRewardSumHistory;
import static com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUtils.computeNewStakes;
import static com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUtils.readableNonZeroHistory;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.StakeInfoHelperTest.DEFAULT_CONFIG;
import static com.hedera.node.app.service.token.impl.test.util.CommonTestUtils.assertUnsupportedConstructor;

import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUtils;
import com.hedera.node.config.data.StakingConfig;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class EndOfStakingPeriodUtilsTest {
    private static final long MAX_STAKE = 10_000L;
    private static final long STAKE_REWARD_START = 1234L;
    private static final long UNCLAIMED_STAKE_REWARD_START = STAKE_REWARD_START / 10;
    private static final int STAKE_TO_REWARD = 345;
    private static final int STAKE_TO_NOT_REWARD = 155;
    private static final StakingNodeInfo STAKING_INFO = StakingNodeInfo.newBuilder()
            .nodeNumber(34)
            .minStake(10_000L)
            .maxStake(MAX_STAKE)
            .stakeToReward(STAKE_TO_REWARD)
            .stakeToNotReward(STAKE_TO_NOT_REWARD)
            .stakeRewardStart(STAKE_REWARD_START)
            .unclaimedStakeRewardStart(UNCLAIMED_STAKE_REWARD_START)
            .stake(500)
            .rewardSumHistory(List.of(2L, 1L, 0L))
            .weight(5)
            .build();
    private static final StakingConfig STAKING_CONFIG = DEFAULT_CONFIG.getConfigData(StakingConfig.class);

    @Test
    void throwsInConstructor() {
        assertUnsupportedConstructor(EndOfStakingPeriodUtils.class);
    }

    @Test
    void readableNonZeroHistoryFromEmptyRewards() {
        final var result = readableNonZeroHistory(Collections.emptyList());
        Assertions.assertThat(result).isEqualTo("[]");
    }

    @Test
    void readableNonZeroHistoryFromNoZeroRewards() {
        final var result = readableNonZeroHistory(List.of(1L, 2L, 3L, 4L, 5L));
        Assertions.assertThat(result).isEqualTo("[1, 2, 3, 4, 5]");
    }

    @Test
    void readableNonZeroHistoryFromOneZeroRewards() {
        final var result = readableNonZeroHistory(List.of(1L, 2L, 3L, 0L, 5L));
        Assertions.assertThat(result).isEqualTo("[1, 2, 3]");
    }

    @Test
    void readableNonZeroHistoryFromMultipleZeroRewards() {
        final var result = readableNonZeroHistory(List.of(1L, 2L, 0L, 0L, 3L, 0L, 0L, 4L, 0L));
        Assertions.assertThat(result).isEqualTo("[1, 2]");
    }

    @Test
    void calculatesUpdatedRewardsSumHistoryWithRateLimiting() {
        final var rewardRate = 1_000_000;
        final var maxRewardRate = rewardRate / 2;

        final var result = computeExtendedRewardSumHistory(STAKING_INFO, rewardRate, maxRewardRate, true);

        Assertions.assertThat(result.rewardSumHistory()).isEqualTo(List.of(maxRewardRate + 2L, 2L, 1L));
        Assertions.assertThat(result.pendingRewardRate()).isEqualTo(maxRewardRate);
    }

    @Test
    void calculatesUpdatedRewardsSumHistoryAsExpectedForNodeWithGreaterThanMinStakeAndNoMoreThanMaxStake() {
        final var rewardRate = 1_000_000;

        final var result = computeExtendedRewardSumHistory(STAKING_INFO, rewardRate, Long.MAX_VALUE, true);

        Assertions.assertThat(result.rewardSumHistory()).isEqualTo(List.of(1_000_002L, 2L, 1L));
        Assertions.assertThat(result.pendingRewardRate()).isEqualTo(1_000_000L);
    }

    @Test
    void calculatesUpdatedRewardsSumHistoryAsExpectedForNodeWithGreaterThanMaxStake() {
        final var rewardRate = 1_000_000;

        final var stakingInfo = STAKING_INFO
                .copyBuilder()
                .stakeRewardStart(2 * STAKING_INFO.maxStake())
                .build();
        final var result = computeExtendedRewardSumHistory(stakingInfo, rewardRate, Long.MAX_VALUE, true);

        Assertions.assertThat(result.rewardSumHistory()).isEqualTo(List.of(500_002L, 2L, 1L));
        Assertions.assertThat(result.pendingRewardRate()).isEqualTo(500_000L);
    }

    @Test
    void usesBiArithmeticForRewardRateDownScaling() {
        final var excessStake = 2 * STAKING_INFO.maxStake();
        final var rewardRate = Long.MAX_VALUE / (MAX_STAKE - 1);
        final var expectedScaledRate = BigInteger.valueOf(rewardRate)
                .multiply(BigInteger.valueOf(MAX_STAKE))
                .divide(BigInteger.valueOf(excessStake))
                .longValueExact();

        final var stakingInfo =
                STAKING_INFO.copyBuilder().stakeRewardStart(excessStake).build();
        final var result = computeExtendedRewardSumHistory(stakingInfo, rewardRate, Long.MAX_VALUE, true);

        Assertions.assertThat(result.rewardSumHistory()).isEqualTo(List.of(expectedScaledRate + 2L, 2L, 1L));
        Assertions.assertThat(result.pendingRewardRate()).isEqualTo(expectedScaledRate);
    }

    @Test
    void calculatesUpdatedRewardsSumHistoryAsExpectedForNodeWithLessThanMinStakeWhenMinIsReqForReward() {
        final var rewardRate = 1_000_000_000;

        final var stakingInfo = STAKING_INFO.copyBuilder().stake(0).build();
        final var result = computeExtendedRewardSumHistory(stakingInfo, rewardRate, Long.MAX_VALUE, true);

        Assertions.assertThat(result.rewardSumHistory()).isEqualTo(List.of(2L, 2L, 1L));
        Assertions.assertThat(result.pendingRewardRate()).isZero();
    }

    @Test
    void calculatesUpdatedSumHistoryAsExpectedForNodeWithLessThanMinStakeWhenMinIsNotReqForReward() {
        final var rewardRate = 1_000_000_000;

        final var stakingInfo = STAKING_INFO
                .copyBuilder()
                .stake(0)
                .stakeRewardStart(STAKING_INFO.minStake() - 1)
                .build();
        final var result = computeExtendedRewardSumHistory(stakingInfo, rewardRate, Long.MAX_VALUE, false);

        Assertions.assertThat(result.rewardSumHistory()).isEqualTo(List.of(1000000002L, 2L, 1L));
        Assertions.assertThat(result.pendingRewardRate()).isEqualTo(rewardRate);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void computeStakeNullArg() {
        Assertions.assertThatThrownBy(() -> computeNewStakes(null, STAKING_CONFIG))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void computeStakeTotalStakeGreaterThanMaxStake() {
        final var maxStake = STAKE_TO_REWARD + STAKE_TO_NOT_REWARD - 1;
        final var input = STAKING_INFO.copyBuilder().maxStake(maxStake).build();

        final var result = computeNewStakes(input, STAKING_CONFIG);
        Assertions.assertThat(result.stake()).isEqualTo(maxStake);
        Assertions.assertThat(result.stakeRewardStart()).isEqualTo(STAKE_TO_REWARD);
    }

    @Test
    void computeStakeTotalStakeLessThanMinStake() {
        final var input = STAKING_INFO
                .copyBuilder()
                .minStake(STAKE_TO_REWARD + STAKE_TO_NOT_REWARD + 1)
                .build();

        final var result = computeNewStakes(input, STAKING_CONFIG);
        Assertions.assertThat(result.stake()).isZero();
        Assertions.assertThat(result.stakeRewardStart()).isEqualTo(STAKE_TO_REWARD);
    }

    @Test
    void computeStakeTotalStakeInBetweenMinStakeAndMaxStake() {
        final var input = STAKING_INFO
                .copyBuilder()
                .minStake(STAKE_TO_REWARD + STAKE_TO_NOT_REWARD - 1)
                .maxStake(STAKE_TO_REWARD + STAKE_TO_NOT_REWARD + 1)
                .build();

        final var result = computeNewStakes(input, STAKING_CONFIG);
        Assertions.assertThat(result.stake()).isEqualTo(STAKE_TO_REWARD + STAKE_TO_NOT_REWARD);
        Assertions.assertThat(result.stakeRewardStart()).isEqualTo(STAKE_TO_REWARD);
    }
}
