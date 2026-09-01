// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.staking;

import static com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper.MAX_PENDING_REWARDS;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper.analyzeStakingAccounts;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper.requiresExternalization;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.StakeInfoHelperTest.DEFAULT_CONFIG;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import java.util.LinkedHashSet;
import java.util.List;
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
        writableAccountStore.put(account.copyBuilder().stakedNodeId(0L).build());
        final var analysis = analyzeStakingAccounts(writableAccountStore, Set.of(), Set.of());
        assertThat(analysis.rewardReceivers()).contains(account.accountId());
    }

    @Test
    void getsAllRewardReceiversForBalanceChanges() {
        writableAccountStore.put(account.copyBuilder().tinybarBalance(1000L).build());
        final var analysis = analyzeStakingAccounts(writableAccountStore, Set.of(), Set.of());
        assertThat(analysis.rewardReceivers()).contains(account.accountId());
    }

    @Test
    void getsAllRewardReceiversIfAlreadyStakedToNode() {
        final var analysis = analyzeStakingAccounts(writableAccountStore, Set.of(account.accountId()), Set.of());
        assertThat(analysis.rewardReceivers()).contains(account.accountId());
    }

    @Test
    void getsAllRewardReceiversIfExplicitlyStakedToNode() {
        final var alreadyStakedToNodeRewardReceiver =
                AccountID.newBuilder().accountNum(payerId.accountNum()).build();
        final var analysis =
                analyzeStakingAccounts(writableAccountStore, Set.of(alreadyStakedToNodeRewardReceiver), Set.of());
        assertThat(analysis.rewardReceivers()).contains(alreadyStakedToNodeRewardReceiver);
    }

    @Test
    void analysisPreservesSpecialCanonicalAndExplicitReceiverOrder() {
        final var store = mock(WritableAccountStore.class);
        final var stakerId = id(1001L);
        final var canonicalId = id(1002L);
        final var stakeeId = id(1003L);
        final var explicitId = id(1004L);
        final var prePaidId = id(1005L);

        final var originalStaker =
                account(stakerId).stakedAccountId(stakeeId).tinybarBalance(100L).build();
        final var currentStaker =
                originalStaker.copyBuilder().tinybarBalance(101L).build();
        final var originalCanonical =
                account(canonicalId).stakedNodeId(1L).tinybarBalance(200L).build();
        final var currentCanonical =
                originalCanonical.copyBuilder().tinybarBalance(201L).build();
        final var originalStakee = account(stakeeId).stakedNodeId(2L).build();
        final var explicit = account(explicitId).stakedNodeId(3L).build();
        final var prePaid = account(prePaidId).stakedNodeId(4L).build();

        given(store.modifiedAccountsInState()).willReturn(new LinkedHashSet<>(List.of(stakerId, canonicalId)));
        given(store.getOriginalValue(stakerId)).willReturn(originalStaker);
        given(store.get(stakerId)).willReturn(currentStaker);
        given(store.getOriginalValue(canonicalId)).willReturn(originalCanonical);
        given(store.get(canonicalId)).willReturn(currentCanonical);
        given(store.getOriginalValue(stakeeId)).willReturn(originalStakee);
        given(store.get(stakeeId)).willReturn(originalStakee);
        given(store.get(explicitId)).willReturn(explicit);
        given(store.getOriginalValue(explicitId)).willReturn(explicit);
        given(store.getOriginalValue(prePaidId)).willReturn(prePaid);

        final var analysis = analyzeStakingAccounts(store, new LinkedHashSet<>(List.of(explicitId)), Set.of(prePaidId));

        assertThat(analysis.modifiedAccounts())
                .extracting(StakingRewardsHelper.ModifiedAccountAnalysis::accountId)
                .containsExactly(stakerId, canonicalId);
        assertThat(analysis.rewardReceivers()).containsExactly(stakeeId, canonicalId, explicitId);
        assertThat(analysis.stakedToMeAdjustmentReceivers()).containsExactly(stakeeId);
        assertThat(analysis.originalAccounts())
                .containsEntry(stakerId, originalStaker)
                .containsEntry(canonicalId, originalCanonical)
                .containsEntry(stakeeId, originalStakee)
                .containsEntry(explicitId, explicit)
                .containsEntry(prePaidId, prePaid);
        verify(store).modifiedAccountsInState();
    }

    @Test
    void zeroWholeHbarStakedToMeAdjustmentStillCountsAsStakingWork() {
        final var store = mock(WritableAccountStore.class);
        final var stakerId = id(2001L);
        final var stakeeId = id(2002L);
        final var original =
                account(stakerId).stakedAccountId(stakeeId).tinybarBalance(1L).build();
        final var current = original.copyBuilder().tinybarBalance(2L).build();

        given(store.modifiedAccountsInState()).willReturn(Set.of(stakerId));
        given(store.getOriginalValue(stakerId)).willReturn(original);
        given(store.get(stakerId)).willReturn(current);
        final var stakee = account(stakeeId).build();
        given(store.getOriginalValue(stakeeId)).willReturn(stakee);
        given(store.get(stakeeId)).willReturn(stakee);

        final var analysis = analyzeStakingAccounts(store, Set.of(), Set.of());

        assertThat(analysis.rewardReceivers()).isEmpty();
        assertThat(analysis.stakedToMeAdjustmentReceivers()).containsExactly(stakeeId);
        assertThat(analysis.hasStakingWork()).isTrue();
    }

    @Test
    void reusedScratchDoesNotLeakReceiversFromAPriorAnalysis() {
        final var store = mock(WritableAccountStore.class);
        final var firstId = id(4001L);
        final var secondId = id(4002L);
        final var first = account(firstId).stakedNodeId(1L).tinybarBalance(10L).build();
        final var firstModified = first.copyBuilder().tinybarBalance(11L).build();
        final var second =
                account(secondId).stakedNodeId(1L).tinybarBalance(20L).build();
        final var secondModified = second.copyBuilder().tinybarBalance(21L).build();
        final var scratch = new StakingRewardsHelper.StakingAnalysisScratch();

        given(store.modifiedAccountsInState()).willReturn(Set.of(firstId));
        given(store.getOriginalValue(firstId)).willReturn(first);
        given(store.get(firstId)).willReturn(firstModified);
        final var firstAnalysis = analyzeStakingAccounts(store, Set.of(), Set.of(), scratch);
        assertThat(firstAnalysis.rewardReceivers()).containsExactly(firstId);

        given(store.modifiedAccountsInState()).willReturn(Set.of(secondId));
        given(store.getOriginalValue(secondId)).willReturn(second);
        given(store.get(secondId)).willReturn(secondModified);
        final var secondAnalysis = analyzeStakingAccounts(store, Set.of(), Set.of(), scratch);
        assertThat(secondAnalysis.rewardReceivers()).containsExactly(secondId);
        assertThat(secondAnalysis.rewardReceivers()).doesNotContain(firstId);
    }

    @Test
    void keyOnlyStyleChangeHasNoStakingWork() {
        final var store = mock(WritableAccountStore.class);
        final var id = id(3001L);
        final var original = account(id).stakedNodeId(1L).build();

        given(store.modifiedAccountsInState()).willReturn(Set.of(id));
        given(store.getOriginalValue(id)).willReturn(original);
        given(store.get(id)).willReturn(original);

        final var analysis = analyzeStakingAccounts(store, Set.of(), Set.of());

        assertThat(analysis.rewardReceivers()).isEmpty();
        assertThat(analysis.stakedToMeAdjustmentReceivers()).isEmpty();
        assertThat(analysis.hasStakeMetadataChanges()).isFalse();
        assertThat(analysis.hasStakingWork()).isFalse();
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

    private static AccountID id(final long accountNum) {
        return AccountID.newBuilder().accountNum(accountNum).build();
    }

    private static Account.Builder account(final AccountID id) {
        return Account.newBuilder().accountId(id);
    }
}
