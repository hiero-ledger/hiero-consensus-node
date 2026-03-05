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
import com.hedera.node.app.service.token.DenominationConverter;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.app.service.token.impl.handlers.staking.StakePeriodManager;
import com.hedera.node.app.service.token.impl.handlers.staking.StakeRewardCalculatorImpl;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
        subject = new StakeRewardCalculatorImpl(stakePeriodManager, new DenominationConverter(8));
    }

    @Test
    void zeroRewardsForMissingNodeStakeInfo() {
        final var reward = StakeRewardCalculatorImpl.computeRewardFromDetails(
                Account.newBuilder().build(), null, 321, 123, 100_000_000L);
        assertEquals(0, reward);
    }

    @Test
    void zeroRewardsForDeletedNodeStakeInfo() {
        final var stakingInfo = StakingNodeInfo.newBuilder().deleted(true).build();
        final var reward = StakeRewardCalculatorImpl.computeRewardFromDetails(
                Account.newBuilder().build(), stakingInfo, 321, 123, 100_000_000L);
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
        given(account.stakedToMe()).willReturn(98 * 100_000_000L);
        given(account.tinybarBalance()).willReturn(2 * 100_000_000L);
        given(account.stakeAtStartOfLastRewardedPeriod()).willReturn(90 * 100_000_000L);

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
        given(account.stakedToMe()).willReturn(100 * 100_000_000L);
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

    @ParameterizedTest
    @MethodSource("decimalsAndSubunits")
    /* default */ void computeRewardFromDetailsScalesWithSubunitsPerWholeUnit(
            final int decimals, final long subunitsPerWholeUnit) {
        // Use whole-unit counts that won't overflow long at 18 decimals
        final long wholeUnits = decimals <= 8 ? 100L : 5L;
        final long totalStake = wholeUnits * subunitsPerWholeUnit;

        // rewardHistory: [5, 0, 0, ...]
        final var history = newRewardHistory();
        final var info = StakingNodeInfo.newBuilder().rewardSumHistory(history).build();

        final var acct = Account.newBuilder()
                .tinybarBalance(totalStake)
                .stakedToMe(0L)
                .stakeAtStartOfLastRewardedPeriod(-1L)
                .build();

        // currentStakePeriod=10, effectiveStart=8 → rewardFrom=1
        // reward = totalStake / subunitsPerWholeUnit * (5 - 0) = wholeUnits * 5
        final long reward = StakeRewardCalculatorImpl.computeRewardFromDetails(acct, info, 10, 8, subunitsPerWholeUnit);

        assertEquals(wholeUnits * 5, reward);
    }

    @ParameterizedTest
    @MethodSource("decimalsAndSubunits")
    /* default */ void computeRewardFromDetailsTruncatesFractionalWholeUnits(
            final int decimals, final long subunitsPerWholeUnit) {
        // For decimals > 0, fractional whole units are truncated by integer division
        // For decimals == 0, subunitsPerWholeUnit == 1, so there are no fractional units
        if (decimals == 0) {
            return;
        }

        final long wholeUnits = decimals <= 8 ? 100L : 5L;
        // Add half a whole unit (fractional)
        final long totalStake = wholeUnits * subunitsPerWholeUnit + subunitsPerWholeUnit / 2;

        final var history = newRewardHistory();
        final var info = StakingNodeInfo.newBuilder().rewardSumHistory(history).build();

        final var acct = Account.newBuilder()
                .tinybarBalance(totalStake)
                .stakedToMe(0L)
                .stakeAtStartOfLastRewardedPeriod(-1L)
                .build();

        // Integer division truncates the half unit → same as wholeUnits * 5
        final long reward = StakeRewardCalculatorImpl.computeRewardFromDetails(acct, info, 10, 8, subunitsPerWholeUnit);

        assertEquals(wholeUnits * 5, reward);
    }

    @ParameterizedTest
    @MethodSource("decimalsAndSubunits")
    /* default */ void computeRewardFromDetailsWithStakeAtStartOfLastRewardedPeriod(
            final int decimals, final long subunitsPerWholeUnit) {
        final long wholeUnits = decimals <= 8 ? 100L : 5L;
        final long priorWholeUnits = decimals <= 8 ? 90L : 4L;

        // rewardHistory: [6, 3, 1, 0, 0, ...]
        final var history = newRewardHistory();
        history.set(0, 6L);
        history.set(1, 3L);
        history.set(2, 1L);

        final var info = StakingNodeInfo.newBuilder().rewardSumHistory(history).build();

        final var acct = Account.newBuilder()
                .tinybarBalance(2 * subunitsPerWholeUnit)
                .stakedToMe((wholeUnits - 2) * subunitsPerWholeUnit)
                .stakeAtStartOfLastRewardedPeriod(priorWholeUnits * subunitsPerWholeUnit)
                .build();

        // currentStakePeriod=10, effectiveStart=6 → rewardFrom=3
        // Two-step: priorWholeUnits * (rewardHistory[2] - rewardHistory[3])
        //         + wholeUnits * (rewardHistory[0] - rewardHistory[2])
        // = priorWholeUnits * (1 - 0) + wholeUnits * (6 - 1)
        final long expected = priorWholeUnits + wholeUnits * 5;
        final long reward = StakeRewardCalculatorImpl.computeRewardFromDetails(acct, info, 10, 6, subunitsPerWholeUnit);

        assertEquals(expected, reward);
    }

    /* default */ static Stream<Arguments> decimalsAndSubunits() {
        return Stream.of(
                Arguments.of(0, 1L), Arguments.of(8, 100_000_000L), Arguments.of(18, 1_000_000_000_000_000_000L));
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
