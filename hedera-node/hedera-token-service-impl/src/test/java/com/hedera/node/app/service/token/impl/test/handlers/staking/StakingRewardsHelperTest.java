// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.staking;

import static com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper.MAX_PENDING_REWARDS;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper.requiresExternalization;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.StakeInfoHelperTest.DEFAULT_CONFIG;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class StakingRewardsHelperTest extends CryptoTokenHandlerTestBase {

    @LoggingTarget
    private LogCaptor logCaptor;

    @Mock
    private ConfigProvider configProvider;

    @LoggingSubject
    private StakingRewardsHelper subject;

    @BeforeEach
    public void setUp() {
        super.setUp();
        refreshWritableStores();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(DEFAULT_CONFIG, 1));
        subject = new StakingRewardsHelper(configProvider);
    }

    @Test
    void onlyNonZeroRewardsIncludedInAccountAmounts() {
        final var zeroRewardId = AccountID.newBuilder().accountNum(1234L).build();
        final var nonZeroRewardId = AccountID.newBuilder().accountNum(4321L).build();
        final var someRewards = Map.of(zeroRewardId, 0L, nonZeroRewardId, 1L);
        final var paidStakingRewards = StakingRewardsHelper.asAccountAmounts(someRewards);
        assertThat(paidStakingRewards)
                .containsExactly(AccountAmount.newBuilder()
                        .accountID(nonZeroRewardId)
                        .amount(1L)
                        .build());
    }

    @Test
    void emptyRewardsPaidDoesNotNeedExternalizing() {
        assertThat(requiresExternalization(Map.of())).isFalse();
    }

    @Test
    void onlyZeroRewardPaidDoesNotNeedExternalizing() {
        final var zeroRewardId = AccountID.newBuilder().accountNum(1234L).build();
        assertThat(requiresExternalization(Map.of(zeroRewardId, 0L))).isFalse();
    }

    @Test
    void nonZeroRewardsPaidNeedsExternalizing() {
        final var zeroRewardId = AccountID.newBuilder().accountNum(1234L).build();
        final var nonZeroRewardId = AccountID.newBuilder().accountNum(4321L).build();
        final var someRewards = Map.of(zeroRewardId, 0L, nonZeroRewardId, 1L);
        assertThat(requiresExternalization(someRewards)).isTrue();
    }

    @Test
    void getsAllRewardReceiversForStakeMetaChanges() {
        final var stakeToMeRewardReceiver = AccountID.newBuilder()
                .accountNum(account.accountId().accountNum())
                .build();
        final var explicitRewardReceiver =
                AccountID.newBuilder().accountNum(1234567L).build();

        final var stakeToMeRewardReceivers = Set.of(stakeToMeRewardReceiver);
        final var explicitRewardReceivers = Set.of(explicitRewardReceiver);
        writableAccountStore.put(account.copyBuilder().stakedNodeId(0L).build());
        final var rewardReceivers = StakingRewardsHelper.getAllRewardReceivers(
                writableAccountStore, stakeToMeRewardReceivers, explicitRewardReceivers);
        assertThat(rewardReceivers).contains(stakeToMeRewardReceiver);
    }

    @Test
    void getsAllRewardReceiversForBalanceChanges() {
        final var stakeToMeRewardReceiver = AccountID.newBuilder()
                .accountNum(account.accountId().accountNum())
                .build();
        final var explicitRewardReceiver =
                AccountID.newBuilder().accountNum(1234567L).build();

        final var stakeToMeRewardReceivers = Set.of(stakeToMeRewardReceiver);
        final var explicitRewardReceivers = Set.of(explicitRewardReceiver);
        writableAccountStore.put(account.copyBuilder().tinybarBalance(1000L).build());
        final var rewardReceivers = StakingRewardsHelper.getAllRewardReceivers(
                writableAccountStore, stakeToMeRewardReceivers, explicitRewardReceivers);
        assertThat(rewardReceivers).contains(stakeToMeRewardReceiver);
    }

    @Test
    void getsAllRewardReceiversIfAlreadyStakedToNode() {
        final var stakeToMeRewardReceiver = AccountID.newBuilder()
                .accountNum(account.accountId().accountNum())
                .build();
        final var explicitRewardReceiver =
                AccountID.newBuilder().accountNum(1234567L).build();

        final var stakeToMeRewardReceivers = Set.of(stakeToMeRewardReceiver);
        final var explicitRewardReceivers = Set.of(explicitRewardReceiver);
        final var rewardReceivers = StakingRewardsHelper.getAllRewardReceivers(
                writableAccountStore, stakeToMeRewardReceivers, explicitRewardReceivers);
        assertThat(rewardReceivers).contains(stakeToMeRewardReceiver);
    }

    @Test
    void getsAllRewardReceiversIfExplicitlyStakedToNode() {
        final var alreadyStakedToNodeRewardReceiver =
                AccountID.newBuilder().accountNum(payerId.accountNum()).build();
        final var explicitRewardReceiver =
                AccountID.newBuilder().accountNum(1234567L).build();

        final var stakeToMeRewardReceivers = Set.of(alreadyStakedToNodeRewardReceiver);
        final var explicitRewardReceivers = Set.of(explicitRewardReceiver);
        final var rewardReceivers = StakingRewardsHelper.getAllRewardReceivers(
                writableAccountStore, stakeToMeRewardReceivers, explicitRewardReceivers);
        assertThat(rewardReceivers).contains(alreadyStakedToNodeRewardReceiver);
    }

    @Test
    void decreasesPendingRewardsAccurately() {
        assertThat(writableRewardsStore.get().pendingRewards()).isEqualTo(1000L);
        subject.decreasePendingRewardsBy(writableStakingInfoStore, writableRewardsStore, 100L, node0Id.number());
        assertThat(writableRewardsStore.get().pendingRewards()).isEqualTo(900L);
    }

    @Test
    void decreasesPendingRewardsToZeroIfNegative() {
        assertThat(writableRewardsStore.get().pendingRewards()).isEqualTo(1000L);
        subject.decreasePendingRewardsBy(writableStakingInfoStore, writableRewardsStore, 2000L, node0Id.number());
        assertThat(writableRewardsStore.get().pendingRewards()).isEqualTo(0);
        assertThat(logCaptor.errorLogs())
                .contains("Pending rewards decreased by 2000 to a meaningless -1000, fixing to zero hbar");
    }

    @Test
    void decreasesPendingRewardsToZeroInStakingInfoMapIfNegative() {
        assertThat(writableStakingInfoStore.get(0).pendingRewards()).isEqualTo(1000000L);
        assertThat(writableRewardsStore.get().pendingRewards()).isEqualTo(1000L);

        subject.decreasePendingRewardsBy(writableStakingInfoStore, writableRewardsStore, 2000000L, node0Id.number());

        assertThat(writableRewardsStore.get().pendingRewards()).isEqualTo(0);
        assertThat(writableStakingInfoStore.get(0).pendingRewards()).isEqualTo(0L);

        assertThat(logCaptor.errorLogs())
                .contains(
                        "Pending rewards decreased by 2000000 to a meaningless -1999000, fixing to zero hbar",
                        "Pending rewards decreased by 2000000 to a meaningless -1000000 for node 0, fixing to zero hbar");
    }

    @Test
    void increasesPendingRewardsAccurately() {
        assertThat(writableRewardsStore.get().pendingRewards()).isEqualTo(1000L);
        final var copyStakingInfo =
                subject.increasePendingRewardsBy(writableRewardsStore, 100L, writableStakingInfoStore.get(0L));
        assertThat(writableRewardsStore.get().pendingRewards()).isEqualTo(1100L);
    }

    @Test
    void increasesPendingRewardsByZeroIfStkingInfoShowsDeleted() {
        writableStakingInfoStore.put(
                node0Id.number(), node0Info.copyBuilder().deleted(true).build());
        assertThat(writableStakingInfoStore.get(0).pendingRewards()).isEqualTo(1000000L);

        final var copyStakingInfo =
                subject.increasePendingRewardsBy(writableRewardsStore, 100L, writableStakingInfoStore.get(0L));

        assertThat(copyStakingInfo.pendingRewards()).isEqualTo(0L);
    }

    @Test
    void increasesPendingRewardsByMaxValueIfVeryLargeNumber() {
        assertThat(writableStakingInfoStore.get(0).pendingRewards()).isEqualTo(1000000L);
        assertThat(writableRewardsStore.get().pendingRewards()).isEqualTo(1000L);

        final var copyStakingInfo = subject.increasePendingRewardsBy(
                writableRewardsStore, Long.MAX_VALUE, writableStakingInfoStore.get(0L));

        assertThat(copyStakingInfo.pendingRewards()).isEqualTo(MAX_PENDING_REWARDS);
        assertThat(writableRewardsStore.get().pendingRewards()).isEqualTo(MAX_PENDING_REWARDS);

        assertThat(logCaptor.errorLogs())
                .contains(
                        "Pending rewards increased by 9223372036854775807 to an un-payable 9223372036854775807, fixing to 50B hbar",
                        "Pending rewards increased by 9223372036854775807 to an un-payable 9223372036854775807 for node 0, fixing to 50B hbar");
    }
}
