// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.staking;

import static com.hedera.node.app.service.token.impl.handlers.staking.StakePeriodManager.ZONE_UTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willCallRealMethod;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.Units;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.app.service.token.impl.handlers.staking.StakePeriodManager;
import com.hedera.node.app.service.token.impl.handlers.staking.StakeRewardCalculatorImpl;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StakeRewardCalculatorImplTest {
    private static final Instant consensusTime = Instant.ofEpochSecond(12345678910L);
    private static final long TODAY_NUMBER =
            LocalDate.ofInstant(Instant.ofEpochSecond(12345678910L), ZONE_UTC).toEpochDay();
    private static final int REWARD_HISTORY_SIZE = 366;

    @Mock
    private StakePeriodManager stakePeriodManager;

    @Mock
    private WritableStakingInfoStore stakingInfoStore;

    @Mock
    private StakingNodeInfo stakingNodeInfo;

    @Mock
    private ReadableNetworkStakingRewardsStore stakingRewardsStore;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private Account account;

    private List<Long> rewardHistory;

    private StakeRewardCalculatorImpl subject;

    @BeforeEach
    void setUp() {
        rewardHistory = newRewardHistory();
        subject = new StakeRewardCalculatorImpl(stakePeriodManager);
    }

    @Test
    void zeroRewardsForMissingNodeStakeInfo() {
        final var reward = StakeRewardCalculatorImpl.computeRewardFromDetails(
                Account.newBuilder().build(), null, 321, 123);
        assertEquals(0, reward);
    }

    @Test
    void zeroRewardsForDeletedNodeStakeInfo() {
        final var stakingInfo = StakingNodeInfo.newBuilder().deleted(true).build();
        final var reward = StakeRewardCalculatorImpl.computeRewardFromDetails(
                Account.newBuilder().build(), stakingInfo, 321, 123);
        assertEquals(0, reward);
    }

    @Test
    void delegatesEpochSecondAtStartOfPeriod() {
        given(stakePeriodManager.epochSecondAtStartOfPeriod(123)).willReturn(456L);
        assertEquals(456L, subject.epochSecondAtStartOfPeriod(123));
    }

    @Test
    void calculatesRewardsAppropriatelyIfBalanceAtStartOfLastRewardedPeriodIsSet() {
        rewardHistory.set(0, 6L);
        rewardHistory.set(1, 3L);
        rewardHistory.set(2, 1L);
        setUpMocks();
        given(stakingInfoStore.getOriginalValue(0L)).willReturn(stakingNodeInfo);
        given(stakePeriodManager.currentStakePeriod()).willReturn(TODAY_NUMBER);
        given(stakingNodeInfo.rewardSumHistory()).willReturn(rewardHistory);
        // Staked node ID of -1 will return a node ID address of 0
        given(account.stakedNodeId()).willReturn(-1L);
        given(account.declineReward()).willReturn(false);
        given(account.stakedToMe()).willReturn(98 * Units.HBARS_TO_TINYBARS);
        given(account.tinybarBalance()).willReturn(2 * Units.HBARS_TO_TINYBARS);
        given(account.stakeAtStartOfLastRewardedPeriod()).willReturn(90 * Units.HBARS_TO_TINYBARS);

        given(account.stakePeriodStart()).willReturn(TODAY_NUMBER - 4);
        // (98+2) * (6-1) + 90 * (1-0) = 590;
        var reward = subject.computePendingReward(account, stakingInfoStore, stakingRewardsStore, consensusTime);

        assertEquals(590, reward);

        given(account.stakePeriodStart()).willReturn(TODAY_NUMBER - 3);
        // (98+2) * (6-3) + 90 * (3-1) = 480;
        reward = subject.computePendingReward(account, stakingInfoStore, stakingRewardsStore, consensusTime);

        assertEquals(480, reward);

        given(account.stakePeriodStart()).willReturn(TODAY_NUMBER - 2);
        // (98+2) * (6-6) + 90 * (6-3) = 270;
        reward = subject.computePendingReward(account, stakingInfoStore, stakingRewardsStore, consensusTime);

        assertEquals(270, reward);
    }

    @Test
    void estimatesPendingRewardsForStateView() {
        final var todayNum = 300L;

        given(stakePeriodManager.estimatedCurrentStakePeriod()).willReturn(todayNum);
        given(stakingNodeInfo.rewardSumHistory()).willReturn(rewardHistory);
        given(account.stakePeriodStart()).willReturn(todayNum - 2);
        given(account.stakeAtStartOfLastRewardedPeriod()).willReturn(-1L);
        given(account.declineReward()).willReturn(false);
        given(account.stakedToMe()).willReturn(100 * Units.HBARS_TO_TINYBARS);
        given(stakePeriodManager.effectivePeriod(todayNum - 2)).willReturn(todayNum - 2);
        given(stakePeriodManager.isEstimatedRewardable(todayNum - 2, stakingRewardsStore))
                .willReturn(true);

        final long reward = subject.estimatePendingRewards(account, stakingNodeInfo, stakingRewardsStore);

        assertEquals(500, reward);

        // if declinedReward
        given(account.declineReward()).willReturn(true);
        assertEquals(0L, subject.estimatePendingRewards(account, stakingNodeInfo, stakingRewardsStore));
    }

    @Test
    void onlyEstimatesPendingRewardsIfRewardable() {
        final var todayNum = 300L;

        given(account.stakePeriodStart()).willReturn(todayNum - 2);
        given(stakePeriodManager.effectivePeriod(todayNum - 2)).willReturn(todayNum - 2);

        final long reward = subject.estimatePendingRewards(account, stakingNodeInfo, stakingRewardsStore);

        assertEquals(0, reward);
    }

    @Test
    void withDeletedStakingNodeInfo() {
        setUpMocks();

        var deletedStakingNodeInfo = mock(StakingNodeInfo.class);
        given(deletedStakingNodeInfo.deleted()).willReturn(true);

        given(stakingInfoStore.getOriginalValue(0L)).willReturn(deletedStakingNodeInfo);
        var reward = subject.computePendingReward(account, stakingInfoStore, stakingRewardsStore, consensusTime);

        assertEquals(0, reward);
    }

    @Test
    void withNullStakingNodeInfo() {
        setUpMocks();

        given(stakingInfoStore.getOriginalValue(0L)).willReturn(null);
        var reward = subject.computePendingReward(account, stakingInfoStore, stakingRewardsStore, consensusTime);

        assertEquals(0, reward);
    }

    @Test
    void withNonRewardableConsensusTime() {
        given(stakePeriodManager.effectivePeriod(account.stakePeriodStart())).willReturn(1L);
        given(stakePeriodManager.isRewardable(1L, stakingRewardsStore)).willReturn(false);

        var reward = subject.computePendingReward(account, stakingInfoStore, stakingRewardsStore, consensusTime);

        assertEquals(0, reward);
    }

    private void setUpMocks() {
        given(stakePeriodManager.firstNonRewardableStakePeriod(stakingRewardsStore))
                .willReturn(TODAY_NUMBER);
        willCallRealMethod().given(stakePeriodManager).effectivePeriod(anyLong());
        willCallRealMethod().given(stakePeriodManager).isRewardable(anyLong(), any());
    }

    private static List<Long> newRewardHistory() {
        final var rewardHistory = IntStream.range(0, REWARD_HISTORY_SIZE)
                .mapToObj(i -> 0L)
                .collect(Collectors.toCollection(() -> new ArrayList<>(REWARD_HISTORY_SIZE)));
        rewardHistory.set(0, 5L);
        return rewardHistory;
    }
}
