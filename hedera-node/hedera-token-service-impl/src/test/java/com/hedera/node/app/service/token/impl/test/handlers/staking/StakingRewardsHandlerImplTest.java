// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.staking;

import static com.hedera.hapi.node.base.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hedera.node.app.service.token.impl.TokenServiceImpl.HBARS_TO_TINYBARS;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingUtilities.roundedToHbar;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingUtilities.totalStake;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.staking.StakeInfoHelper;
import com.hedera.node.app.service.token.impl.handlers.staking.StakePeriodManager;
import com.hedera.node.app.service.token.impl.handlers.staking.StakeRewardCalculatorImpl;
import com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsDistributor;
import com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHandlerImpl;
import com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper;
import com.hedera.node.app.service.token.impl.handlers.staking.StakingUtilities;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.service.token.records.CryptoDeleteStreamBuilder;
import com.hedera.node.app.service.token.records.FinalizeContext;
import com.hedera.node.app.spi.workflows.record.DeleteCapableTransactionStreamBuilder;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.HederaConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StakingRewardsHandlerImplTest extends CryptoTokenHandlerTestBase {

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ConfigProvider configProvider;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private FinalizeContext context;

    @Mock
    private CryptoDeleteStreamBuilder recordBuilder;

    @Mock
    private EntityIdFactory entityIdFactory;

    private final InstantSource instantSource = InstantSource.system();

    private StakingRewardsHandlerImpl subject;
    private StakePeriodManager stakePeriodManager;
    private StakingRewardsDistributor rewardsPayer;
    private StakeInfoHelper stakeInfoHelper;
    private StakeRewardCalculatorImpl stakeRewardCalculator;
    private StakingRewardsHelper stakingRewardHelper;
    protected final EntityNumber node0Id = EntityNumber.newBuilder().number(0L).build();
    protected final EntityNumber node1Id = EntityNumber.newBuilder().number(1L).build();

    @BeforeEach
    public void setUp() {
        super.setUp();
        refreshWritableStores();

        given(configProvider.getConfiguration()).willReturn(versionedConfig);
        given(context.configuration()).willReturn(configuration);
        given(context.consensusTime()).willReturn(consensusInstant);
        givenStoresAndConfig(context);

        stakingRewardHelper = new StakingRewardsHelper(configProvider);
        stakePeriodManager = new StakePeriodManager(configProvider, instantSource);
        stakeRewardCalculator = new StakeRewardCalculatorImpl(stakePeriodManager);
        rewardsPayer = new StakingRewardsDistributor(stakingRewardHelper, stakeRewardCalculator);
        stakeInfoHelper = new StakeInfoHelper();
        subject = new StakingRewardsHandlerImpl(rewardsPayer, stakePeriodManager, stakeInfoHelper, entityIdFactory);
    }

    @Test
    void testCoverageForPrivateConstructor()
            throws NoSuchMethodException, InstantiationException, IllegalAccessException {
        final Constructor<StakingUtilities> constructor = StakingUtilities.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        try {
            constructor.newInstance();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertThat(cause.getClass()).isEqualTo(UnsupportedOperationException.class);
        }
    }

    @Test
    void testStakeMetaChangesForNullOriginalAccount() {
        noStakeChanges();
        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());
        final var modifiedAccount = writableAccountStore.get(payerId);
        assertThat(modifiedAccount).isNotNull();
        assertThat(StakingUtilities.hasStakeMetaChanges(null, modifiedAccount)).isTrue();
    }

    @Test
    void changingKeyOnlyIsNotRewardSituation() {
        final var stakedToMeBefore = account.stakedToMe();
        final var stakePeriodStartBefore = account.stakePeriodStart();
        final var stakeAtStartOfLastRewardedPeriodBefore = account.stakeAtStartOfLastRewardedPeriod();

        noStakeChanges();

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        assertThat(rewards).isEmpty();
        final var modifiedAccount = writableAccountStore.get(payerId);
        final var stakedToMeAfter = modifiedAccount.stakedToMe();
        final var stakePeriodStartAfter = modifiedAccount.stakePeriodStart();
        final var stakeAtStartOfLastRewardedPeriodAfter = modifiedAccount.stakeAtStartOfLastRewardedPeriod();

        assertThat(stakedToMeAfter).isEqualTo(stakedToMeBefore);
        assertThat(stakePeriodStartAfter).isEqualTo(stakePeriodStartBefore);
        assertThat(stakeAtStartOfLastRewardedPeriodAfter).isEqualTo(stakeAtStartOfLastRewardedPeriodBefore);
    }

    @Test
    void rewardsWhenStakingFieldsModified() {
        final var stakedToMeBefore = account.stakedToMe();
        final var stakePeriodStartBefore = account.stakePeriodStart();

        randomStakeNodeChanges();
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // earned zero rewards due to zero stake
        assertThat(rewards).hasSize(1);
        assertThat(rewards).containsEntry(payerId, 0L);

        final var modifiedAccount = writableAccountStore.get(payerId);
        // stakedToMe will not change as this is not staked by another account
        final var stakedToMeAfter = modifiedAccount.stakedToMe();
        // These should change as staking is triggered
        final var stakePeriodStartAfter = modifiedAccount.stakePeriodStart();
        final var stakeAtStartOfLastRewardedPeriodAfter = modifiedAccount.stakeAtStartOfLastRewardedPeriod();

        stakePeriodManager.setCurrentStakePeriodFor(consensusInstant);
        final var expectedStakePeriodStart = stakePeriodManager.currentStakePeriod();
        assertThat(stakedToMeAfter).isEqualTo(stakedToMeBefore);
        assertThat(stakePeriodStartAfter).isNotEqualTo(stakePeriodStartBefore).isEqualTo(expectedStakePeriodStart);
        // staking metadata is updated, so stakeAtStartOfLastRewardedPeriod will be set to -1
        assertThat(stakeAtStartOfLastRewardedPeriodAfter).isEqualTo(-1);
    }

    @Test
    void anAccountThatStartedStakingBeforeCurrentPeriodAndHasntBeenRewardedUnclaimsStakeWhenChangingElection() {
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(55L * HBARS_TO_TINYBARS)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withStakedToMe(0L)
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore));

        // Change node, so to trigger rewards
        writableAccountStore.put(writableAccountStore
                .get(payerId)
                .copyBuilder()
                .stakedNodeId(0L)
                .stakeAtStartOfLastRewardedPeriod(-1)
                .build());

        // We use next stake period to trigger rewards
        Instant nextDayInstant = LocalDate.ofEpochDay(stakePeriodStart + 1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();

        given(context.consensusTime()).willReturn(nextDayInstant);
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());

        mockEntityIdFactory();

        subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        final var payerAfter = writableAccountStore.get(payerId);
        final var node1Info = writableStakingInfoState.get(node1Id);

        assertThat(payerAfter.tinybarBalance()).isEqualTo(node1Info.unclaimedStakeRewardStart());
    }

    @Test
    void anAccountThatStartedStakingBeforeCurrentPeriodAndWasRewardedDaysAgoUnclaimsStakeWhenChangingElection() {
        final var newBalance = 55L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(newBalance)
                .withStakeAtStartOfLastRewardPeriod(newBalance / 5)
                .withStakedNodeId(node1Id.number())
                .withStakedToMe(0)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore));

        // Change node, so to trigger rewards
        writableAccountStore.put(
                writableAccountStore.get(payerId).copyBuilder().stakedNodeId(0L).build());

        // We use next stake period to trigger rewards.
        Instant nextDayInstant = originalInstant.plus(2, ChronoUnit.DAYS);

        given(context.consensusTime()).willReturn(nextDayInstant);
        stakePeriodManager.setCurrentStakePeriodFor(nextDayInstant);

        mockEntityIdFactory();

        subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        final var node1Info = writableStakingInfoState.get(node1Id);
        // Since the node is rewarded in last period the unclaimed reward will be stakeAtStartOfLastRewardPeriod.
        // But the stakePeriodSTart is not the previous period, so the unclaimed reward will be total stake of the node.
        assertThat(node1Info.unclaimedStakeRewardStart()).isEqualTo(newBalance);
    }

    @Test
    void anAccountThatStartedStakingBeforeCurrentPeriodAndWasRewardedTodayUnclaimsStakeStartWhenChangingElection() {
        final var newBalance = 55L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(newBalance)
                .withStakeAtStartOfLastRewardPeriod(newBalance / 5)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withStakedToMe(0L)
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore));
        mockEntityIdFactory();

        // Change node, so to trigger rewards
        writableAccountStore.put(
                writableAccountStore.get(payerId).copyBuilder().stakedNodeId(0L).build());

        // We use next stake period to trigger rewards
        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart + 1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());

        subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        final var node1Info = writableStakingInfoState.get(node1Id);
        // Since the node is rewarded in last period and stakePeriodStart is the previous period
        // the unclaimed reward will be stakeAtStartOfLastRewardPeriod.
        assertThat(node1Info.unclaimedStakeRewardStart()).isEqualTo(newBalance / 5);
    }

    @Test
    void anAccountThatStartedStakingAtCurrentPeriodDoesntUnclaimStakeWhenChangingElection() {
        final var newBalance = 555L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(newBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore));

        mockEntityIdFactory();

        // Change node, so to trigger rewards
        writableAccountStore.put(account.copyBuilder().stakedNodeId(0L).build());

        given(context.consensusTime()).willReturn(stakePeriodStartInstant);
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);

        subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        final var node1Info = writableStakingInfoState.get(node1Id);

        assertThat(node1Info.unclaimedStakeRewardStart()).isZero();
    }

    @Test
    void anAccountThatDeclineRewardsDoesntUnclaimStakeWhenChangingElection() {
        final var newBalance = 555L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(newBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(true)
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore));
        mockEntityIdFactory();

        // Change node, so to trigger rewards
        writableAccountStore.put(
                writableAccountStore.get(payerId).copyBuilder().stakedNodeId(0L).build());

        given(context.consensusTime()).willReturn(originalInstant);
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);

        subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        final var node1Info = writableStakingInfoState.get(node1Id);

        assertThat(node1Info.unclaimedStakeRewardStart()).isZero();
    }

    //    @Test
    //    void anAutoCreatedAccountShouldNotHaveStakeStartUpdated() {
    //        final var newId = AccountID.newBuilder().accountNum(10000000000L).build();
    //        writableAccountStore.put(givenValidAccountBuilder().accountId(newId).build());
    //
    //        given(handleContext.consensusNow()).willReturn(stakePeriodStartInstant);
    //        given(handleContext.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
    //
    //
    //        subject.applyStakingRewards(handleContext);
    //
    //        assertThat(0).isEqualTo(writableAccountStore.get(newId).stakeAtStartOfLastRewardedPeriod());
    //    }

    @Test
    void earningZeroRewardsWithStartBeforeLastNonRewardableStillUpdatesSASOLARP() {
        final var account = mock(Account.class);
        final var manager = mock(StakePeriodManager.class);
        given(manager.isRewardable(account, readableRewardsStore)).willReturn(true);
        given(account.stakePeriodStart()).willReturn(2L);

        final StakingRewardsHandlerImpl impl =
                new StakingRewardsHandlerImpl(rewardsPayer, manager, stakeInfoHelper, entityIdFactory);

        assertThat(impl.shouldUpdateStakeAtStartOfLastRewardPeriod(account, true, 0L, readableRewardsStore))
                .isTrue();
    }

    @Test
    void anAccountWithAlreadyCollectedRewardShouldNotHaveStakeStartUpdated() {
        final var newBalance = 555L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(newBalance)
                .withStakeAtStartOfLastRewardPeriod(newBalance - 1)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore));
        mockEntityIdFactory();

        writableAccountStore.put(writableAccountStore
                .get(payerId)
                .copyBuilder()
                .tinybarBalance(2 * newBalance)
                .build());

        given(context.consensusTime()).willReturn(stakePeriodStartInstant);
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);

        subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        final var node1Info = writableStakingInfoState.get(node1Id);

        assertThat(node1Info.unclaimedStakeRewardStart()).isZero();
    }

    @Test
    void calculatesRewardIfNeededStakingToNode() {
        final var accountBalance = 55L * HBARS_TO_TINYBARS;
        final var ownerBalance = 11L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withStakedToMe(0L)
                .withDeclineReward(false)
                .withStakedNodeId(node1Id.number())
                .withDeleted(false)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withStakedToMe(0L)
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        final var node1InfoBefore = writableStakingInfoState.get(node1Id);
        final var node0InfoBefore = writableStakingInfoState.get(node0Id);

        assertThat(node0InfoBefore.pendingRewards()).isEqualTo(1000000L);
        assertThat(node1InfoBefore.pendingRewards()).isEqualTo(1000000L);

        writableAccountStore.put(account.copyBuilder()
                .tinybarBalance(accountBalance - HBARS_TO_TINYBARS)
                .stakedNodeId(0L)
                .build());
        writableAccountStore.put(ownerAccount
                .copyBuilder()
                .tinybarBalance(ownerBalance + HBARS_TO_TINYBARS)
                .stakedNodeId(0L)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart + 2)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());

        mockEntityIdFactory();

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        final var node1InfoAfter = writableStakingInfoState.get(node1Id);
        final var node0InfoAfter = writableStakingInfoState.get(node0Id);

        assertThat(rewards).hasSize(1).containsEntry(payerId, 5500L).doesNotContainKey(ownerId);

        assertThat(node1InfoAfter.stakeToReward()).isEqualTo(node1InfoBefore.stakeToReward() - accountBalance);

        final var modifiedPayer = writableAccountStore.get(payerId);
        final var modifiedOwner = writableAccountStore.get(ownerId);

        assertThat(node0InfoAfter.stakeToReward())
                .isEqualTo(node0InfoBefore.stakeToReward()
                        + roundedToHbar(totalStake(modifiedPayer) + roundedToHbar(totalStake(modifiedOwner))));

        assertThat(node1InfoAfter.unclaimedStakeRewardStart())
                .isEqualTo(node1InfoBefore.unclaimedStakeRewardStart() + accountBalance);

        assertThat(node1Info.unclaimedStakeRewardStart()).isZero();

        assertThat(node0InfoAfter.pendingRewards()).isEqualTo(1000000L);
        assertThat(node1InfoAfter.pendingRewards()).isEqualTo(994500L);
    }

    @Test
    void doesNotAwardStakeFromDeletedAccount() {
        final var accountBalance = 555L * HBARS_TO_TINYBARS;
        final var ownerBalance = 111L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(true)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        writableAccountStore.put(payerAccountBefore
                .copyBuilder()
                .tinybarBalance(0)
                .stakedNodeId(0L)
                .build());
        writableAccountStore.put(ownerAccountBefore
                .copyBuilder()
                .tinybarBalance(ownerBalance + accountBalance)
                .stakedNodeId(0L)
                .build());
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(123L * HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart + 2)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(1);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(ownerId);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());
        assertThat(rewards).hasSize(1);
        // because the transferId is owner for the deleted payer account
        assertThat(rewards).containsEntry(ownerId, 178900L);
    }

    @Test
    void nodeStakeNotCorruptedWhenStakeeDeletedAfterStaking() {
        // Account A (payer) is staked to account W (owner). W is staked to node 0 and has already
        // been deleted in a prior transaction. The node's stakeToReward was correctly zeroed by
        // that deletion. Now a new transaction changes A's balance while A still carries
        // stakedAccountId=W. Without the deleted-stakee guard in updateStakedToMeFor, the handler
        // would mutate W's stakedToMe, re-enter W into the modified-accounts set, and then run a
        // withdrawStake on node 0 with no matching awardStake (because W is deleted) — driving the
        // node's stakeToReward below its true value on every subsequent transaction touching A.
        final var stakerBalance = 25L * HBARS_TO_TINYBARS;
        // A is NOT deleted; it is staked to W (ownerId)
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(stakerBalance)
                .withStakedAccountId(ownerId)
                .withStakedToMe(0L)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .build();
        // W IS deleted; balance=0 (funds already transferred), stakedToMe=25 HBAR (A's contribution
        // that was never cleaned up), stakedNodeId=0 (also never cleared on deletion)
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(0L)
                .withStakedNodeId(node0Id.number())
                .withStakedToMe(stakerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(true)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        final var node0StakeBeforeTx = writableStakingInfoState.get(node0Id).stakeToReward();

        // Simulate a new transaction that increases A's balance by 5 HBAR
        writableAccountStore.put(payerAccountBefore
                .copyBuilder()
                .tinybarBalance(stakerBalance + 5L * HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart + 2)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // The node's stakeToReward must not be lowered by a spurious withdraw for the deleted stakee
        final var node0InfoAfter = writableStakingInfoState.get(node0Id);
        assertThat(node0InfoAfter.stakeToReward()).isEqualTo(node0StakeBeforeTx);
    }

    @Test
    void stakingEffectsWorkAsExpectedWhenStakingToNodeWithNoStakingMetaChanges() {
        final var accountBalance = 55L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withStakedToMe(0L)
                .withDeclineReward(false)
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore));
        final var initialStakePeriodStart = payerAccountBefore.stakePeriodStart();

        final var node1InfoBefore = writableStakingInfoState.get(node1Id);

        mockEntityIdFactory();

        writableAccountStore.put(account.copyBuilder()
                .tinybarBalance(accountBalance - HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart + 2)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());

        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        final var node1InfoAfter = writableStakingInfoState.get(node1Id);

        assertThat(rewards).hasSize(1).containsEntry(payerId, 5500L);

        assertThat(node1InfoAfter.stake()).isEqualTo(node1InfoBefore.stake());
        assertThat(node1InfoAfter.unclaimedStakeRewardStart()).isEqualTo(node1InfoBefore.unclaimedStakeRewardStart());
        assertThat(node1Info.unclaimedStakeRewardStart()).isZero();

        final var modifiedAccount = writableAccountStore.get(payerId);
        assertThat(modifiedAccount.tinybarBalance())
                .isEqualTo(accountBalance - HBARS_TO_TINYBARS + rewards.get(payerId));
        assertThat(modifiedAccount.stakePeriodStart()).isNotEqualTo(initialStakePeriodStart);
        assertThat(modifiedAccount.stakePeriodStart()).isNotEqualTo(stakePeriodStart + 2);
    }

    @Test
    void stakingEffectsWorkAsExpectedWhenStakingToNodeWithNoStakingMetaChangesAndNoReward() {
        final var accountBalance = 555L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore));
        final var initialStakePeriodStart = payerAccountBefore.stakePeriodStart();
        mockEntityIdFactory();

        final var node1InfoBefore = writableStakingInfoState.get(node1Id);

        writableAccountStore.put(account.copyBuilder()
                .tinybarBalance(accountBalance - HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        final var node1InfoAfter = writableStakingInfoState.get(node1Id);

        // No rewards rewarded
        assertThat(rewards).hasSize(1);
        assertThat(rewards).containsEntry(payerId, 0L);

        assertThat(node1InfoAfter.stake()).isEqualTo(node1InfoBefore.stake());
        assertThat(node1InfoAfter.unclaimedStakeRewardStart()).isEqualTo(node1InfoBefore.unclaimedStakeRewardStart());
        assertThat(node1Info.unclaimedStakeRewardStart()).isZero();

        final var modifiedAccount = writableAccountStore.get(payerId);
        assertThat(modifiedAccount.tinybarBalance()).isEqualTo(accountBalance - HBARS_TO_TINYBARS);
        assertThat(modifiedAccount.stakePeriodStart()).isEqualTo(initialStakePeriodStart);
    }

    @Test
    void sasolarpMgmtWorksAsExpectedWhenStakingToNodeWithNoStakingMetaChangesAndNoReward() {
        final var payerInitialBalance = 55L * HBARS_TO_TINYBARS;
        final var payerAfterBalance = 54L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(payerInitialBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withStakedToMe(0L)
                .withStakedNodeId(node1Id.number())
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore));

        final var initialStakePeriodStart = payerAccountBefore.stakePeriodStart();
        final var node1InfoBefore = writableStakingInfoState.get(node1Id);
        writableAccountStore.put(payerAccountBefore
                .copyBuilder()
                .tinybarBalance(payerAfterBalance)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart + 1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        mockEntityIdFactory();
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());

        // No rewards rewarded
        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        final var node1InfoAfter = writableStakingInfoState.get(node1Id);

        // Since it has not declined rewards and has zero stake, no rewards rewarded
        assertThat(rewards).hasSize(1);
        assertThat(rewards).containsEntry(payerId, 0L);

        assertThat(node1InfoAfter.stake()).isEqualTo(node1InfoBefore.stake());
        assertThat(node1InfoAfter.unclaimedStakeRewardStart()).isEqualTo(node1InfoBefore.unclaimedStakeRewardStart());
        assertThat(node1Info.unclaimedStakeRewardStart()).isZero();

        final var modifiedAccount = writableAccountStore.get(payerId);
        assertThat(modifiedAccount.tinybarBalance()).isEqualTo(payerInitialBalance - HBARS_TO_TINYBARS);
        assertThat(modifiedAccount.stakePeriodStart()).isEqualTo(stakePeriodStart);
        assertThat(modifiedAccount.stakeAtStartOfLastRewardedPeriod()).isEqualTo(payerInitialBalance);
    }

    @Test
    void stakingEffectsWorkAsExpectedWhenStakingToAccount() {
        final var payerInitialBalance = 55L * HBARS_TO_TINYBARS;
        final var ownerInitialBalance = 11L * HBARS_TO_TINYBARS;
        final var payerLaterBalance = 54L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(payerInitialBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withStakedToMe(0L)
                .withDeleted(false)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerInitialBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withStakedToMe(0L)
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        final var node1InfoBefore = writableStakingInfoState.get(node1Id);

        writableAccountStore.put(payerAccountBefore
                .copyBuilder()
                .tinybarBalance(payerLaterBalance)
                .stakedAccountId(ownerId)
                .build());
        mockEntityIdFactory();
        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart + 2)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        final var node1InfoAfter = writableStakingInfoState.get(node1Id);

        assertThat(rewards).hasSize(1).containsEntry(payerId, 5500L);

        assertThat(node1InfoAfter.stakeToReward()).isEqualTo(node1InfoBefore.stakeToReward() - payerInitialBalance);
        assertThat(node1InfoAfter.unclaimedStakeRewardStart()).isEqualTo(payerInitialBalance);
        // stake field of the account is updated once a day

        final var modifiedOwner = writableAccountStore.get(ownerId);
        final var modifiedPayer = writableAccountStore.get(payerId);

        assertThat(modifiedOwner.stakedToMe()).isEqualTo(payerLaterBalance);
        // stakePeriodStart is updated only when reward is applied
        assertThat(modifiedOwner.stakePeriodStart()).isEqualTo(stakePeriodStart);

        assertThat(modifiedPayer.stakedToMe()).isEqualTo(payerAccountBefore.stakedToMe());
        // Only worthwhile to update stakedPeriodStart for an account staking to a node
        assertThat(modifiedPayer.stakePeriodStart()).isEqualTo(stakePeriodStart);
    }

    @Test
    void userSwitchesStakingFromAccountToNode() {
        // payer is staked to owner, has account balance of 55L, and no rewards
        // payer switches stake from owner to node
        // payer should get reward from the node
        // owner should get no reward
        final var accountBalance = 55L * HBARS_TO_TINYBARS;
        final var ownerBalance = 11L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(accountBalance / 5)
                .withStakedAccountId(ownerId)
                .withStakedToMe(0)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withStakedToMe(0L)
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        // transfer from payer to owner
        // change payer stake from owner account to node 1
        writableAccountStore.put(payerAccountBefore
                .copyBuilder()
                .tinybarBalance(accountBalance - HBARS_TO_TINYBARS)
                .stakedNodeId(node1Id.number())
                .build());
        writableAccountStore.put(ownerAccount
                .copyBuilder()
                .tinybarBalance(ownerBalance + HBARS_TO_TINYBARS)
                .build());

        // run forward two periods
        final Instant nextDayInstant = originalInstant.plus(2, ChronoUnit.DAYS);
        given(context.consensusTime()).willReturn(nextDayInstant);
        stakePeriodManager.setCurrentStakePeriodFor(nextDayInstant);

        mockEntityIdFactory();
        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());
        assertThat(rewards).hasSize(0);
    }

    @Test
    void userSwitchesStakingFromNothingToAccount() {
        // payer is staked to owner, has account balance of 55L, and no rewards
        // payer switches stake from owner to node
        // payer should get no reward
        // owner should get no reward
        final var accountBalance = 55L * HBARS_TO_TINYBARS;
        final var ownerBalance = 11L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakedNodeId(-1L)
                .withStakedToMe(0)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(true)
                .build();
        addToState(Map.of(payerId, payerAccountBefore));

        // transfer from payer to owner
        // change payer stake from owner account to node 1
        writableAccountStore.put(payerAccountBefore
                .copyBuilder()
                .tinybarBalance(accountBalance - HBARS_TO_TINYBARS)
                .stakedAccountId(ownerId)
                .build());
        writableAccountStore.put(ownerAccount
                .copyBuilder()
                .tinybarBalance(ownerBalance + HBARS_TO_TINYBARS)
                .build());

        // run forward two periods
        Instant nextDayInstant = originalInstant.plus(2, ChronoUnit.DAYS);
        given(context.consensusTime()).willReturn(nextDayInstant);
        stakePeriodManager.setCurrentStakePeriodFor(nextDayInstant);

        mockEntityIdFactory();
        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());
        // confirm no rewards
        assertThat(rewards).hasSize(0);
    }

    @Test
    void userSwitchesStakingFromAccountToNothing() {
        // payer is staked to owner, has account balance of 55L, and no rewards
        // payer switches stake from owner to nothing (node -1)
        // payer should get no reward
        // owner should still get the reward from before the switch
        final var accountBalance = 55L * HBARS_TO_TINYBARS;
        final var ownerBalance = 11L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakedAccountId(ownerId)
                .withStakedToMe(0)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(true)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withStakedNodeId(node1Id.number())
                .withStakedToMe(0L)
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        // transfer from payer to owner
        // change payer stake from owner account to node -1
        writableAccountStore.put(payerAccountBefore
                .copyBuilder()
                .tinybarBalance(accountBalance - HBARS_TO_TINYBARS)
                .stakedNodeId(-1) // switch to staking to nothing
                .build());
        writableAccountStore.put(ownerAccount
                .copyBuilder()
                .tinybarBalance(ownerBalance + HBARS_TO_TINYBARS)
                .build());

        // run forward two periods
        Instant nextDayInstant = originalInstant.plus(2, ChronoUnit.DAYS);
        given(context.consensusTime()).willReturn(nextDayInstant);
        stakePeriodManager.setCurrentStakePeriodFor(nextDayInstant);

        mockEntityIdFactory();
        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());
        // check that owner still gets reward, but payer gets nothing
        assertThat(rewards).hasSize(1).containsEntry(ownerId, 2200L);
    }

    @Test
    void rewardsUltimateBeneficiaryInsteadOfDeletedAccount() {
        final var accountBalance = 555L * HBARS_TO_TINYBARS;
        final var ownerBalance = 111L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(true)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        writableAccountStore.put(payerAccountBefore
                .copyBuilder()
                .tinybarBalance(0)
                .stakedNodeId(0L)
                .build());
        writableAccountStore.put(ownerAccountBefore
                .copyBuilder()
                .tinybarBalance(ownerBalance + accountBalance)
                .stakedNodeId(0L)
                .build());
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(123L * HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart + 2)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(1);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(ownerId);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());
        assertThat(rewards).hasSize(1);
        // because the transferId is owner for the deleted payer account
        assertThat(rewards).containsEntry(ownerId, 178900L);
    }

    @Test
    void deletedAccountRewardRedirectDoesNotAdvanceBeneficiaryStakeMetadata() {
        final var accountBalance = 555L * HBARS_TO_TINYBARS;
        final var ownerBalance = 111L * HBARS_TO_TINYBARS;
        final var currentStakePeriod = stakePeriodStart + 2;
        // The deleted account is rewardable (it began staking two periods ago) ...
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        // ... while its beneficiary only began staking in the current period, so the beneficiary is
        // NOT yet rewardable on its own stake.
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(currentStakePeriod)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        writableAccountStore.put(payerAccountBefore
                .copyBuilder()
                .tinybarBalance(0)
                .stakedNodeId(0L)
                .deleted(true)
                .build());
        writableAccountStore.put(ownerAccountBefore
                .copyBuilder()
                .tinybarBalance(ownerBalance + accountBalance)
                .stakedNodeId(0L)
                .build());
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(123L * HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(1);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(ownerId);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());
        final var beneficiary = writableAccountStore.get(ownerId);

        // The deleted account's earned reward is still forwarded to, and recorded against, the
        // beneficiary -- this part is intended and must be preserved.
        assertThat(rewards).containsEntry(ownerId, 178900L);
        assertThat(writableAccountStore.get(payerId).deleted()).isTrue();
        assertThat(beneficiary.tinybarBalance()).isEqualTo(ownerBalance + accountBalance + 178900L);

        // But the redirect must NOT advance the beneficiary's own staking metadata: its
        // stakePeriodStart stays put (not moved back to currentStakePeriod - 1) and its
        // stakeAtStartOfLastRewardedPeriod is left untouched.
        assertThat(beneficiary.stakePeriodStart()).isEqualTo(currentStakePeriod);
        assertThat(beneficiary.stakeAtStartOfLastRewardedPeriod()).isEqualTo(-1L);

        // Consequently the beneficiary is still not rewardable in the next period, so it collects no
        // unearned reward from the staking reward account.
        final var nextPeriodInstant = LocalDate.ofEpochDay(currentStakePeriod + 1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        stakePeriodManager.setCurrentStakePeriodFor(nextPeriodInstant);
        final var nextPeriodReward = stakeRewardCalculator.computePendingReward(
                beneficiary, writableStakingInfoStore, readableRewardsStore, nextPeriodInstant);
        assertThat(nextPeriodReward).isZero();
    }

    @Test
    void prepaidRedirectedRewardDoesNotAdvanceBeneficiaryStakeMetadata() {
        final var ownerInitialBalance = 111L * HBARS_TO_TINYBARS;
        final var redirectedReward = 178900L;
        final var currentStakePeriod = stakePeriodStart + 2;
        // A scheduled CryptoDelete handled in a child dispatch pays a deleted account's reward to
        // this beneficiary and reports it (keyed by the beneficiary) as a pre-paid reward to the
        // parent. The beneficiary only began staking in the current period, so it is not rewardable
        // on its own stake; the pre-paid redirected reward must not advance its stake metadata.
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerInitialBalance + redirectedReward)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(currentStakePeriod)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        addToState(Map.of(ownerId, ownerAccountBefore));

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        subject.applyStakingRewards(context, Collections.emptySet(), Map.of(ownerId, redirectedReward));

        final var beneficiary = writableAccountStore.get(ownerId);
        assertThat(beneficiary.stakePeriodStart()).isEqualTo(currentStakePeriod);
        assertThat(beneficiary.stakeAtStartOfLastRewardedPeriod()).isEqualTo(-1L);
    }

    @Test
    void prepaidRedirectedRewardDoesNotInitializeBeneficiaryStakeMetadata() {
        final var ownerInitialBalance = 111L * HBARS_TO_TINYBARS;
        final var redirectedReward = 178900L;
        final var currentStakePeriod = stakePeriodStart + 2;
        // A stakePeriodStart of -1 means the beneficiary was never rewardable on its own stake.
        // Receiving a reward redirected by a scheduled child dispatch must not initialize either
        // field used to track its own staking reward history.
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerInitialBalance + redirectedReward)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(-1L)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        addToState(Map.of(ownerId, ownerAccountBefore));

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        subject.applyStakingRewards(context, Collections.emptySet(), Map.of(ownerId, redirectedReward));

        final var beneficiary = writableAccountStore.get(ownerId);
        assertThat(beneficiary.stakePeriodStart()).isEqualTo(-1L);
        assertThat(beneficiary.stakeAtStartOfLastRewardedPeriod()).isEqualTo(-1L);
    }

    @Test
    void prepaidRewardAdvancesStakeMetadataOfARecipientRewardableOnItsOwnStake() {
        // The other prepaid tests all assert that metadata is NOT advanced, for recipients that are
        // not rewardable on their own stake. This is the positive half, and it is what forces stake
        // metadata to be keyed by the EARNER: a reward a SCHEDULED child dispatch already paid an
        // account for its own stake completes that account's reward lifecycle, so its
        // stakePeriodStart must move or the next transaction pays it a second time for the same
        // periods. Prepaid rewards reach us only as (recipient, amount) pairs, so they are folded
        // into earnedRewards -- the recipient-keyed map never carries them.
        final var ownerBalance = 111L * HBARS_TO_TINYBARS;
        final var prepaidReward = 11_100L;
        final var currentStakePeriod = stakePeriodStart + 2;
        // Unlike the sibling tests, this recipient began staking two periods ago, so it really is
        // rewardable on its own stake and the prepaid reward really is its own.
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance + prepaidReward)
                .withStakedToMe(0L)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        addToState(Map.of(ownerId, ownerAccountBefore));

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        final var rewards =
                subject.applyStakingRewards(context, Collections.emptySet(), Map.of(ownerId, prepaidReward));

        // The prepaid reward is not re-reported in the parent record, and not paid again.
        assertThat(rewards).isEmpty();
        final var recipient = writableAccountStore.get(ownerId);
        assertThat(recipient.tinybarBalance()).isEqualTo(ownerBalance + prepaidReward);

        // But its stake metadata does advance, so it cannot collect the same periods again.
        assertThat(recipient.stakePeriodStart()).isEqualTo(currentStakePeriod - 1);
        assertThat(recipient.stakeAtStartOfLastRewardedPeriod()).isEqualTo(roundedToHbar(ownerBalance + prepaidReward));
    }

    @Test
    void prepaidRewardForAccountWithNoOriginalValueIsHandledSafely() {
        final var beneficiaryBalance = 111L * HBARS_TO_TINYBARS;
        // Must be zero: with a positive amount the reverted (pre-fix) code short-circuits wasRewarded
        // on reward > 0 and never reaches earnedZeroRewardsBecauseOfZeroStake(null, ...), so the NPE
        // this test guards would not be exercised.
        final var redirectedReward = 0L;
        final var currentStakePeriod = stakePeriodStart + 2;
        // Defense-in-depth: a reward may be recorded (keyed by the beneficiary) for an account with no
        // original value -- i.e. one created during this same transaction. Such an account was not
        // rewarded for a prior period, so its stake metadata must not be advanced, and the rewardability
        // gate must not dereference the null original.
        final var beneficiaryModified = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(beneficiaryBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(currentStakePeriod)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        // Seed only the staking reward account; the beneficiary exists solely as a modification, so its
        // original value is null.
        addToState(Map.of());
        writableAccountStore.put(beneficiaryModified);

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        // Must not throw (previously NPE'd on the null original) and must not advance stake metadata.
        subject.applyStakingRewards(context, Collections.emptySet(), Map.of(ownerId, redirectedReward));

        final var beneficiary = writableAccountStore.get(ownerId);
        assertThat(beneficiary.stakePeriodStart()).isEqualTo(currentStakePeriod);
        assertThat(beneficiary.stakeAtStartOfLastRewardedPeriod()).isEqualTo(-1L);
    }

    @Test
    void drainedDeleteAlsoPaysBeneficiaryOwnRewardBeforeAdvancingMetadata() {
        // The drain->delete counterexample: rewardable A is drained (its spendable balance moved
        // elsewhere earlier in the same transaction) and then deleted with independently rewardable,
        // otherwise-untouched B as beneficiary. The delete transfers zero tinybars, so B is absent
        // from the snapshotted reward-receiver set. Paying only A's redirected reward while
        // advancing B's stakePeriodStart would forfeit B's own accrued reward; instead B must be
        // enqueued for its own assessment, paid both amounts, and only then advanced.
        final var payerBalance = 555L * HBARS_TO_TINYBARS;
        final var ownerBalance = 111L * HBARS_TO_TINYBARS;
        final var currentStakePeriod = stakePeriodStart + 2;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(payerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        // Only the drained-and-deleted earner and the funding account are modified; the
        // beneficiary is deliberately untouched (the delete transferred zero to it)
        writableAccountStore.put(
                payerAccountBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(123L * HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(1);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(ownerId);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        // The base fixture's network pendingRewards (1000) is smaller than the amounts paid here
        // and would clamp to zero; seed it large enough that the exact-decrement assertion is
        // meaningful
        writableRewardsStore.put(writableRewardsStore
                .get()
                .copyBuilder()
                .pendingRewards(1_000_000L)
                .build());
        final var networkPendingBefore = writableRewardsStore.pendingRewards();
        final var nodePendingBefore = writableStakingInfoStore.get(0L).pendingRewards();

        // The defining precondition of the drain->delete scenario: the drained-and-deleted earner
        // is in the initial reward-receiver snapshot, while the untouched beneficiary is absent
        final var initialRewardReceivers = StakingRewardsHelper.getAllRewardReceivers(
                writableAccountStore,
                subject.getStakedToMeRewardReceivers(writableAccountStore),
                Collections.emptySet());
        assertThat(initialRewardReceivers).contains(payerId).doesNotContain(ownerId);

        // A's stake is (555 + 1234 stakedToMe) = 1789 whole hbars -> 178900 redirected reward;
        // B's own stake is (111 + 1234 stakedToMe) = 1345 whole hbars -> 134500 own reward
        final var redirectedReward = 178900L;
        final var ownReward = 134500L;
        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // The recipient-keyed result combines the redirect with B's own reward under B
        assertThat(rewards).hasSize(1);
        assertThat(rewards).containsEntry(ownerId, redirectedReward + ownReward);

        final var beneficiary = writableAccountStore.get(ownerId);
        assertThat(beneficiary.tinybarBalance()).isEqualTo(ownerBalance + redirectedReward + ownReward);
        // B's metadata advances only because its own pending reward was actually assessed and paid
        assertThat(beneficiary.stakePeriodStart()).isEqualTo(currentStakePeriod - 1);
        assertThat(beneficiary.stakeAtStartOfLastRewardedPeriod()).isEqualTo(1345L * HBARS_TO_TINYBARS);

        // The deleted earner's reward went to its beneficiary, so its own metadata is untouched
        assertThat(writableAccountStore.get(payerId).stakePeriodStart()).isEqualTo(stakePeriodStart);

        // Both pending-reward liabilities are reduced exactly once, and the funding account is
        // debited by exactly the total paid out
        assertThat(networkPendingBefore - writableRewardsStore.pendingRewards())
                .isEqualTo(redirectedReward + ownReward);
        assertThat(nodePendingBefore - writableStakingInfoStore.get(0L).pendingRewards())
                .isEqualTo(redirectedReward + ownReward);
        final var fundingAccount =
                writableAccountStore.get(AccountID.newBuilder().accountNum(800).build());
        assertThat(fundingAccount.tinybarBalance()).isEqualTo(123L * HBARS_TO_TINYBARS - redirectedReward - ownReward);
    }

    @Test
    void beneficiaryThatIsAlsoNaturalReceiverIsAssessedExactlyOnce() {
        // If the beneficiary was independently modified (so it is already in the snapshotted
        // receiver set), the redirect must not enqueue a second assessment of its own reward
        final var payerBalance = 555L * HBARS_TO_TINYBARS;
        final var ownerBalance = 111L * HBARS_TO_TINYBARS;
        final var currentStakePeriod = stakePeriodStart + 2;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(payerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        writableAccountStore.put(
                payerAccountBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        // The beneficiary also received the deleted account's principal, making it a natural
        // reward receiver on its own
        writableAccountStore.put(ownerAccountBefore
                .copyBuilder()
                .tinybarBalance(ownerBalance + payerBalance)
                .build());
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(123L * HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(1);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(ownerId);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        // The base fixture's network pendingRewards (1000) is smaller than the amounts paid here
        // and would clamp to zero; seed it large enough that the exact-decrement assertion is
        // meaningful
        writableRewardsStore.put(writableRewardsStore
                .get()
                .copyBuilder()
                .pendingRewards(1_000_000L)
                .build());
        final var networkPendingBefore = writableRewardsStore.pendingRewards();
        final var nodePendingBefore = writableStakingInfoStore.get(0L).pendingRewards();

        final var redirectedReward = 178900L;
        final var ownReward = 134500L;
        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // A double assessment would have paid (or decremented) B's own reward twice
        assertThat(rewards).hasSize(1);
        assertThat(rewards).containsEntry(ownerId, redirectedReward + ownReward);
        assertThat(networkPendingBefore - writableRewardsStore.pendingRewards())
                .isEqualTo(redirectedReward + ownReward);
        assertThat(nodePendingBefore - writableStakingInfoStore.get(0L).pendingRewards())
                .isEqualTo(redirectedReward + ownReward);

        final var beneficiary = writableAccountStore.get(ownerId);
        assertThat(beneficiary.tinybarBalance()).isEqualTo(ownerBalance + payerBalance + redirectedReward + ownReward);
        assertThat(beneficiary.stakePeriodStart()).isEqualTo(currentStakePeriod - 1);
    }

    @Test
    void redirectChainsAssessEachEarnerOwnRewardExactlyOnce() {
        // A -> B -> C: both A and B are deleted in this transaction, so A's reward must be redirected
        // PAST the just-deleted B -- whose pre-transaction value still looks live -- to C, the first
        // account not deleted as of this transaction; B's own reward is likewise redirected to C, and
        // C's own reward is assessed too. Each earner is assessed exactly once and nothing is stranded
        // on a deleted account.
        final var payerBalance = 555L * HBARS_TO_TINYBARS;
        final var ownerBalance = 111L * HBARS_TO_TINYBARS;
        final var transferBalance = 222L * HBARS_TO_TINYBARS;
        final var currentStakePeriod = stakePeriodStart + 2;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(payerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        final var transferAccountBefore = new AccountCustomizer()
                .withAccount(transferAccount)
                .withBalance(transferBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        addToState(Map.of(
                payerId, payerAccountBefore, ownerId, ownerAccountBefore, transferAccountId, transferAccountBefore));

        writableAccountStore.put(
                payerAccountBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(
                ownerAccountBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(123L * HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(2);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(ownerId);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(ownerId)).willReturn(transferAccountId);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        // The base fixture's network pendingRewards (1000) is smaller than the amounts paid here
        // and would clamp to zero; seed it large enough that the exact-decrement assertion is
        // meaningful
        writableRewardsStore.put(writableRewardsStore
                .get()
                .copyBuilder()
                .pendingRewards(1_000_000L)
                .build());
        final var networkPendingBefore = writableRewardsStore.pendingRewards();
        final var nodePendingBefore = writableStakingInfoStore.get(0L).pendingRewards();

        final var payerReward = 178900L; // (555 + 1234) whole hbars
        final var ownerReward = 134500L; // (111 + 1234) whole hbars
        final var transferReward = 145600L; // (222 + 1234) whole hbars
        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // Nothing is paid to the deleted A or B; A's, B's and C's rewards are all paid to C, the only
        // account not deleted as of this transaction
        assertThat(rewards).hasSize(1);
        assertThat(rewards).containsEntry(transferAccountId, payerReward + ownerReward + transferReward);
        // The reward is not stranded on the deleted B; the live tail C holds the whole amount
        assertThat(writableAccountStore.get(ownerId).tinybarBalance()).isEqualTo(0);
        assertThat(writableAccountStore.get(transferAccountId).tinybarBalance())
                .isEqualTo(transferBalance + payerReward + ownerReward + transferReward);

        // Every earner in the chain was assessed exactly once against the shared budget
        assertThat(networkPendingBefore - writableRewardsStore.pendingRewards())
                .isEqualTo(payerReward + ownerReward + transferReward);
        assertThat(nodePendingBefore - writableStakingInfoStore.get(0L).pendingRewards())
                .isEqualTo(payerReward + ownerReward + transferReward);

        // Only C was rewarded on its own stake, so only C's metadata advances
        assertThat(writableAccountStore.get(transferAccountId).stakePeriodStart())
                .isEqualTo(currentStakePeriod - 1);
        assertThat(writableAccountStore.get(ownerId).stakePeriodStart()).isEqualTo(stakePeriodStart);
        assertThat(writableAccountStore.get(payerId).stakePeriodStart()).isEqualTo(stakePeriodStart);
    }

    @Test
    void redirectToBeneficiaryCreatedInThisTransactionPaysItWithoutFailing() {
        // A -> B where B was CREATED earlier in this same transaction, so B has no original value.
        // The redirect must not dereference a null original (the historical NPE) and must still pay
        // A's reward to the live B; with no pre-transaction stake, B is never assessed for a reward
        // of its own.
        final var payerBalance = 555L * HBARS_TO_TINYBARS;
        final var currentStakePeriod = stakePeriodStart + 2;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(payerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        // Only A has an original; B is created in this transaction
        addToState(Map.of(payerId, payerAccountBefore));

        writableAccountStore.put(
                payerAccountBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        // B is brand new this transaction (no original), live, staked to a node
        writableAccountStore.put(new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(0L)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(currentStakePeriod)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build());
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(123L * HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(1);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(ownerId);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        writableRewardsStore.put(writableRewardsStore
                .get()
                .copyBuilder()
                .pendingRewards(1_000_000L)
                .build());
        final var networkPendingBefore = writableRewardsStore.pendingRewards();

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // A's reward is paid to the newly created B; only A is assessed (B has no own reward)
        assertThat(rewards).hasSize(1);
        final var paidToB = rewards.get(ownerId);
        assertThat(paidToB).isNotNull().isPositive();
        assertThat(writableAccountStore.get(ownerId).tinybarBalance()).isEqualTo(paidToB);
        assertThat(networkPendingBefore - writableRewardsStore.pendingRewards()).isEqualTo(paidToB);
    }

    @Test
    void emptyFundingBeforeDeletedEarnerLeavesBeneficiaryUntouched() {
        // A -> B, but the staking reward account is empty, so A's reward clamps to zero: the redirect
        // and the beneficiary enqueue are skipped entirely, and the untouched, independently rewardable
        // B keeps its balance, metadata and pending reward for a future period.
        final var payerBalance = 555L * HBARS_TO_TINYBARS;
        final var ownerBalance = 111L * HBARS_TO_TINYBARS;
        final var currentStakePeriod = stakePeriodStart + 2;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(payerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        writableAccountStore.put(
                payerAccountBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        // Staking reward account (0.0.800) is empty
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(0)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        // The redirect is never reached (reward clamps to zero on empty funding), so the deleted-account
        // beneficiary lookups are intentionally not stubbed
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        writableRewardsStore.put(writableRewardsStore
                .get()
                .copyBuilder()
                .pendingRewards(1_000_000L)
                .build());
        final var networkPendingBefore = writableRewardsStore.pendingRewards();

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // Nothing could be paid, so the untouched beneficiary B is left entirely alone
        assertThat(rewards).doesNotContainKey(ownerId);
        assertThat(writableAccountStore.get(ownerId).tinybarBalance()).isEqualTo(ownerBalance);
        assertThat(writableAccountStore.get(ownerId).stakePeriodStart()).isEqualTo(stakePeriodStart);
        // B's (and A's) pending reward is preserved for a future period
        assertThat(writableRewardsStore.pendingRewards()).isEqualTo(networkPendingBefore);
    }

    @Test
    void redirectToAccountStakedBeneficiaryPaysOnlyTheRedirect() {
        // A beneficiary staked to an ACCOUNT cannot have a pending reward of its own, so the
        // redirect must not enqueue an own-reward assessment for it (which would throw on
        // stakedNodeIdOrThrow), and must not touch its stake metadata -- even with a stale
        // stakePeriodStart left over in the rewardable range.
        //
        // Only the redirect is paid here because the beneficiary's own stakee (spenderId) stakes
        // to neither a node nor an account, so it can earn nothing and the settlement pass in
        // applyStakingRewards has nothing to assess. When that stakee IS node-staked, crediting
        // the beneficiary raises the stakee's stakedToMe and the stakee must be settled first --
        // see redirectedRewardSettlesTheBeneficiarysStakeeBeforeRaisingItsStakedToMe.
        final var payerBalance = 555L * HBARS_TO_TINYBARS;
        final var ownerBalance = 111L * HBARS_TO_TINYBARS;
        final var currentStakePeriod = stakePeriodStart + 2;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(payerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedAccountId(spenderId)
                .build();
        final var spenderAccountBefore = new AccountCustomizer()
                .withAccount(spenderAccount)
                .withStakedToMe(0L)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore, spenderId, spenderAccountBefore));

        writableAccountStore.put(
                payerAccountBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(123L * HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(1);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(ownerId);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        final var redirectedReward = 178900L;
        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        assertThat(rewards).hasSize(1);
        assertThat(rewards).containsEntry(ownerId, redirectedReward);

        final var beneficiary = writableAccountStore.get(ownerId);
        assertThat(beneficiary.tinybarBalance()).isEqualTo(ownerBalance + redirectedReward);
        assertThat(beneficiary.stakePeriodStart()).isEqualTo(stakePeriodStart);
        assertThat(beneficiary.stakeAtStartOfLastRewardedPeriod()).isEqualTo(-1L);
    }

    @Test
    void beneficiaryOwnRewardSharesOneFundingBudgetWithRedirects() {
        // The enqueued own-reward assessment must draw from the same payable-rewards budget as the
        // redirect that triggered it, so the funding account can never be over-drawn
        final var payerBalance = 555L * HBARS_TO_TINYBARS;
        final var ownerBalance = 111L * HBARS_TO_TINYBARS;
        final var currentStakePeriod = stakePeriodStart + 2;
        final var redirectedReward = 178900L;
        final var cappedOwnReward = 50000L; // B's full own reward would be 134500
        final var fundingBalance = redirectedReward + cappedOwnReward;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(payerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        writableAccountStore.put(
                payerAccountBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(fundingBalance)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(1);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(ownerId);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        // The base fixture's network pendingRewards (1000) is smaller than the amounts paid here
        // and would clamp to zero; seed it large enough that the exact-decrement assertion is
        // meaningful
        writableRewardsStore.put(writableRewardsStore
                .get()
                .copyBuilder()
                .pendingRewards(1_000_000L)
                .build());
        final var networkPendingBefore = writableRewardsStore.pendingRewards();

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // B's own reward is capped to what remains of the budget after the redirect
        assertThat(rewards).hasSize(1);
        assertThat(rewards).containsEntry(ownerId, redirectedReward + cappedOwnReward);
        assertThat(networkPendingBefore - writableRewardsStore.pendingRewards())
                .isEqualTo(redirectedReward + cappedOwnReward);

        // The funding account is fully drained but never negative
        final var fundingAccount =
                writableAccountStore.get(AccountID.newBuilder().accountNum(800).build());
        assertThat(fundingAccount.tinybarBalance()).isZero();

        // B still earned a positive own reward, so its metadata advances
        assertThat(writableAccountStore.get(ownerId).stakePeriodStart()).isEqualTo(currentStakePeriod - 1);
    }

    @Test
    void beneficiaryOwnRewardClampsToZeroWhenRedirectExhaustsFundingExactly() {
        // Boundary of the shared budget: the funding account holds exactly the redirected reward.
        // The redirect is paid in full and leaves zero remaining, so B is still enqueued (the
        // redirect itself was positive) but its own assessment clamps to zero: nothing more is
        // paid, the funding account lands on exactly zero, and only the redirect is deducted from
        // the pending-reward liabilities. Like any receiver assessed against an exhausted funding
        // account, B still counts as assessed on its own stake, so its metadata advances.
        final var payerBalance = 555L * HBARS_TO_TINYBARS;
        final var ownerBalance = 111L * HBARS_TO_TINYBARS;
        final var currentStakePeriod = stakePeriodStart + 2;
        final var redirectedReward = 178900L;
        final var fundingBalance = redirectedReward; // B's own reward of 134500 must clamp to zero
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(payerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        writableAccountStore.put(
                payerAccountBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(fundingBalance)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(1);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(ownerId);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        // The base fixture's network pendingRewards (1000) is smaller than the amounts paid here
        // and would clamp to zero; seed it large enough that the exact-decrement assertion is
        // meaningful
        writableRewardsStore.put(writableRewardsStore
                .get()
                .copyBuilder()
                .pendingRewards(1_000_000L)
                .build());
        final var networkPendingBefore = writableRewardsStore.pendingRewards();
        final var nodePendingBefore = writableStakingInfoStore.get(0L).pendingRewards();

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // Only the redirect is paid; B's own assessment contributes exactly zero
        assertThat(rewards).hasSize(1);
        assertThat(rewards).containsEntry(ownerId, redirectedReward);
        final var beneficiary = writableAccountStore.get(ownerId);
        assertThat(beneficiary.tinybarBalance()).isEqualTo(ownerBalance + redirectedReward);

        // A reward clamped to zero is never deducted from the pending-reward liabilities, so they
        // shrink by the redirect alone
        assertThat(networkPendingBefore - writableRewardsStore.pendingRewards()).isEqualTo(redirectedReward);
        assertThat(nodePendingBefore - writableStakingInfoStore.get(0L).pendingRewards())
                .isEqualTo(redirectedReward);

        // The funding account lands on exactly zero, never negative
        final var fundingAccount =
                writableAccountStore.get(AccountID.newBuilder().accountNum(800).build());
        assertThat(fundingAccount.tinybarBalance()).isZero();

        // B was assessed on its own stake (for zero), so its metadata advances exactly as for a
        // natural receiver clamped to zero by an empty funding account
        assertThat(beneficiary.stakePeriodStart()).isEqualTo(currentStakePeriod - 1);
        assertThat(beneficiary.stakeAtStartOfLastRewardedPeriod()).isEqualTo(1345L * HBARS_TO_TINYBARS);
    }

    @Test
    void redirectToPrepaidRewardRecipientDoesNotAssessItAgain() {
        // A final recipient already paid by a SCHEDULED child dispatch had any own pending reward
        // assessed there. If a deleted account's reward is then redirected to that same recipient
        // in this distribution, the recipient must NOT be enqueued for a second assessment (its
        // original state still shows a stale stakePeriodStart, so re-assessing would double-pay
        // its own reward)
        final var payerBalance = 555L * HBARS_TO_TINYBARS;
        final var ownerBalance = 111L * HBARS_TO_TINYBARS;
        final var currentStakePeriod = stakePeriodStart + 2;
        final var prepaidReward = 134500L;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(payerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        writableAccountStore.put(
                payerAccountBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(123L * HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(1);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(ownerId);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        writableRewardsStore.put(writableRewardsStore
                .get()
                .copyBuilder()
                .pendingRewards(1_000_000L)
                .build());
        final var networkPendingBefore = writableRewardsStore.pendingRewards();

        final var redirectedReward = 178900L;
        final var rewards =
                subject.applyStakingRewards(context, Collections.emptySet(), Map.of(ownerId, prepaidReward));

        // Only the redirected reward is paid here; the recipient's own reward was prepaid and is
        // neither re-paid nor re-reported in the parent
        assertThat(rewards).hasSize(1);
        assertThat(rewards).containsEntry(ownerId, redirectedReward);
        assertThat(networkPendingBefore - writableRewardsStore.pendingRewards()).isEqualTo(redirectedReward);

        final var beneficiary = writableAccountStore.get(ownerId);
        assertThat(beneficiary.tinybarBalance()).isEqualTo(ownerBalance + redirectedReward);
        // The prepaid entry (folded into the earned-rewards view) still advances the recipient's
        // metadata, since its own reward lifecycle completed in the child dispatch
        assertThat(beneficiary.stakePeriodStart()).isEqualTo(currentStakePeriod - 1);
    }

    @Test
    void multipleRedirectsToSameBeneficiaryAggregateWithoutReassessment() {
        // Two deleted earners redirect to the same untouched beneficiary: the payments aggregate
        // under the beneficiary while its own reward is assessed exactly once, and its remembered
        // stake reflects its original state, not a balance inflated by the redirects
        final var payerBalance = 555L * HBARS_TO_TINYBARS;
        final var spenderBalance = 333L * HBARS_TO_TINYBARS;
        final var ownerBalance = 111L * HBARS_TO_TINYBARS;
        final var currentStakePeriod = stakePeriodStart + 2;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(payerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        final var spenderAccountBefore = new AccountCustomizer()
                .withAccount(spenderAccount)
                .withBalance(spenderBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, spenderId, spenderAccountBefore, ownerId, ownerAccountBefore));

        writableAccountStore.put(
                payerAccountBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(spenderAccountBefore
                .copyBuilder()
                .tinybarBalance(0)
                .deleted(true)
                .build());
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(123L * HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(2);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(ownerId);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(spenderId)).willReturn(ownerId);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        writableRewardsStore.put(writableRewardsStore
                .get()
                .copyBuilder()
                .pendingRewards(1_000_000L)
                .build());
        final var networkPendingBefore = writableRewardsStore.pendingRewards();
        final var nodePendingBefore = writableStakingInfoStore.get(0L).pendingRewards();

        final var payerReward = 178900L; // (555 + 1234 stakedToMe) whole hbars
        final var spenderReward = 156700L; // (333 + 1234 stakedToMe) whole hbars
        final var ownReward = 134500L; // (111 + 1234 stakedToMe) whole hbars
        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // Both redirects and the beneficiary's own reward aggregate under the one recipient
        assertThat(rewards).hasSize(1);
        assertThat(rewards).containsEntry(ownerId, payerReward + spenderReward + ownReward);

        // Each of the three earners was assessed exactly once
        assertThat(networkPendingBefore - writableRewardsStore.pendingRewards())
                .isEqualTo(payerReward + spenderReward + ownReward);
        assertThat(nodePendingBefore - writableStakingInfoStore.get(0L).pendingRewards())
                .isEqualTo(payerReward + spenderReward + ownReward);

        final var beneficiary = writableAccountStore.get(ownerId);
        assertThat(beneficiary.tinybarBalance()).isEqualTo(ownerBalance + payerReward + spenderReward + ownReward);
        assertThat(beneficiary.stakePeriodStart()).isEqualTo(currentStakePeriod - 1);
        // The remembered stake is the beneficiary's ORIGINAL whole-hbar stake, not one inflated
        // by the redirected payments
        assertThat(beneficiary.stakeAtStartOfLastRewardedPeriod()).isEqualTo(1345L * HBARS_TO_TINYBARS);
    }

    @Test
    void redirectToUntouchedDecliningBeneficiaryBurnsRewardAndChangesNothing() {
        // Pins the declineReward treatment of the own-assessment queue: an untouched beneficiary
        // that declines rewards receives nothing from the redirect (the deleted earner's already
        // decremented pending reward simply stays in 0.0.800), and since it was neither paid nor
        // modified it is not reviewed for metadata either -- stakePeriodStart and
        // stakeAtStartOfLastRewardedPeriod stay put even though it is otherwise eligible, and its
        // own pending reward remains fully intact for a future assessment
        final var payerBalance = 555L * HBARS_TO_TINYBARS;
        final var ownerBalance = 111L * HBARS_TO_TINYBARS;
        final var currentStakePeriod = stakePeriodStart + 2;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(payerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(true)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        writableAccountStore.put(
                payerAccountBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(123L * HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(1);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(ownerId);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        writableRewardsStore.put(writableRewardsStore
                .get()
                .copyBuilder()
                .pendingRewards(1_000_000L)
                .build());
        final var networkPendingBefore = writableRewardsStore.pendingRewards();

        final var redirectedReward = 178900L;
        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // The declined redirect is recorded as a zero payment to the beneficiary
        assertThat(rewards).hasSize(1);
        assertThat(rewards).containsEntry(ownerId, 0L);

        // The earner's pending reward was decremented (and burned to 0.0.800) exactly once; the
        // beneficiary's own pending reward was NOT decremented, and nothing left 0.0.800
        assertThat(networkPendingBefore - writableRewardsStore.pendingRewards()).isEqualTo(redirectedReward);
        final var fundingAccount =
                writableAccountStore.get(AccountID.newBuilder().accountNum(800).build());
        assertThat(fundingAccount.tinybarBalance()).isEqualTo(123L * HBARS_TO_TINYBARS);

        // The declining beneficiary is left entirely untouched
        final var beneficiary = writableAccountStore.get(ownerId);
        assertThat(beneficiary.tinybarBalance()).isEqualTo(ownerBalance);
        assertThat(beneficiary.stakePeriodStart()).isEqualTo(stakePeriodStart);
        assertThat(beneficiary.stakeAtStartOfLastRewardedPeriod()).isEqualTo(-1L);
    }

    @Test
    void revertedChildDeleteContributesNoBeneficiaryMappings() {
        // forEachChildRecord also hands back REVERTED child dispatches with their deleted-account
        // beneficiary maps intact; since the revert restored the account, those stale mappings
        // must not be folded into the root builder consulted by the redirect
        final var accountBalance = 555L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(0L)
                .build();
        addToState(Map.of(payerId, payerAccountBefore));

        // The delete was reverted: the account survives with only a balance change
        writableAccountStore.put(payerAccountBefore
                .copyBuilder()
                .tinybarBalance(accountBalance - HBARS_TO_TINYBARS)
                .build());
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(123L * HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart + 2)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);

        // The child recorded a (payer -> owner) mapping before being reverted
        final DeleteCapableTransactionStreamBuilder childBuilder = mock(DeleteCapableTransactionStreamBuilder.class);
        given(childBuilder.status()).willReturn(REVERTED_SUCCESS);
        doAnswer(inv -> {
                    final Consumer<DeleteCapableTransactionStreamBuilder> consumer = inv.getArgument(1);
                    consumer.accept(childBuilder);
                    return null;
                })
                .when(context)
                .forEachChildRecord(eq(DeleteCapableTransactionStreamBuilder.class), any());

        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // The surviving account is rewarded directly, with no redirect in play
        assertThat(rewards).hasSize(1);
        assertThat(rewards).containsEntry(payerId, 178900L);
        // And the reverted child's stale mapping was neither read nor folded into the root builder
        verify(childBuilder, never()).forEachDeletedAccountBeneficiary(any());
        verify(recordBuilder, never()).addBeneficiaryForDeletedAccount(any(), any());
    }

    @Test
    void redirectsRewardForAccountDeletedInChildDispatch() {
        // Same reward situation as rewardsUltimateBeneficiaryInsteadOfDeletedAccount, except the
        // deleted -> beneficiary mapping is recorded on a CHILD dispatch builder (as happens for the
        // inner CryptoDelete of an atomic batch), not on the root builder consulted by the redirect.
        // The handler must fold the child mapping into the root builder, otherwise the redirect loop
        // cannot resolve the beneficiary and staking finalization fails.
        final var accountBalance = 555L * HBARS_TO_TINYBARS;
        final var ownerBalance = 111L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(true)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        writableAccountStore.put(payerAccountBefore
                .copyBuilder()
                .tinybarBalance(0)
                .stakedNodeId(0L)
                .build());
        writableAccountStore.put(ownerAccountBefore
                .copyBuilder()
                .tinybarBalance(ownerBalance + accountBalance)
                .stakedNodeId(0L)
                .build());
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(123L * HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart + 2)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);

        // The root builder starts with an EMPTY deleted-account map; the mapping only exists on the
        // child dispatch builder. Back the root builder mock with a real map so the fold is observable.
        final Map<AccountID, AccountID> rootBeneficiaries = new HashMap<>();
        given(recordBuilder.getNumberOfDeletedAccounts()).willAnswer(inv -> rootBeneficiaries.size());
        given(recordBuilder.getDeletedAccountBeneficiaryFor(any()))
                .willAnswer(inv -> rootBeneficiaries.get(inv.<AccountID>getArgument(0)));
        doAnswer(inv -> {
                    rootBeneficiaries.put(inv.getArgument(0), inv.getArgument(1));
                    return null;
                })
                .when(recordBuilder)
                .addBeneficiaryForDeletedAccount(any(), any());

        // A child dispatch recorded (payer -> owner) on its own builder; expose it via forEachChildRecord.
        final DeleteCapableTransactionStreamBuilder childBuilder = mock(DeleteCapableTransactionStreamBuilder.class);
        doAnswer(inv -> {
                    final BiConsumer<AccountID, AccountID> action = inv.getArgument(0);
                    action.accept(payerId, ownerId);
                    return null;
                })
                .when(childBuilder)
                .forEachDeletedAccountBeneficiary(any());
        doAnswer(inv -> {
                    final Consumer<DeleteCapableTransactionStreamBuilder> consumer = inv.getArgument(1);
                    consumer.accept(childBuilder);
                    return null;
                })
                .when(context)
                .forEachChildRecord(eq(DeleteCapableTransactionStreamBuilder.class), any());

        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // The reward is redirected to the (non-deleted) beneficiary, exactly as in the non-batch case.
        assertThat(rewards).hasSize(1);
        assertThat(rewards).containsEntry(ownerId, 178900L);
        // And the child dispatch's mapping was folded into the root builder.
        assertThat(rootBeneficiaries).containsEntry(payerId, ownerId);
    }

    @Test
    void doesntTrackAnythingIfRedirectBeneficiaryDeclinedReward() {
        final var payerInitialBalance = 555L * HBARS_TO_TINYBARS;
        final var ownerInitialBalance = 111L * HBARS_TO_TINYBARS;
        final var ownerAfterBalance = ownerInitialBalance + payerInitialBalance;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(payerInitialBalance)
                .withStakedToMe(0L)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(true)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withStakedToMe(0L)
                .withBalance(ownerInitialBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(true)
                .withDeleted(false)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        writableAccountStore.put(payerAccountBefore
                .copyBuilder()
                .tinybarBalance(0)
                .stakedNodeId(0L)
                .build());
        writableAccountStore.put(ownerAccountBefore
                .copyBuilder()
                .tinybarBalance(ownerAfterBalance)
                .stakedNodeId(0L)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart + 2)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(1);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(ownerId);
        mockEntityIdFactory();
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());
        // because the transferId is owner and it declined reward
        assertThat(rewards).hasSize(1);
    }

    @Test
    void failsHardIfMoreRedirectsThanDeletedEntitiesAreNeeded() {
        final var accountBalance = 555L * HBARS_TO_TINYBARS;
        final var ownerBalance = 111L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(true)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(true)
                .build();
        final var spenderAccountBefore = new AccountCustomizer()
                .withAccount(spenderAccount)
                .withBalance(0L)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(true)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore, spenderId, spenderAccountBefore));

        writableAccountStore.put(payerAccountBefore
                .copyBuilder()
                .tinybarBalance(0)
                .stakedNodeId(0L)
                .build());
        writableAccountStore.put(ownerAccountBefore
                .copyBuilder()
                .tinybarBalance(ownerBalance + accountBalance)
                .stakedNodeId(0L)
                .build());
        writableAccountStore.put(Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(800).build())
                .tinybarBalance(123L * HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart + 2)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());

        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(2);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(ownerId);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(ownerId)).willReturn(spenderId);
        given(entityIdFactory.newAccountId(800))
                .willReturn(AccountID.newBuilder().accountNum(800).build());

        assertThatThrownBy(() -> subject.applyStakingRewards(context, Collections.emptySet(), emptyMap()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updatesStakedToMeSideEffects() {
        final var accountBalance = 55L * HBARS_TO_TINYBARS;
        final var ownerBalance = 11L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart - 2)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedToMe(0L)
                .withStakedAccountId(ownerId)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart - 2)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(node0Id.number())
                .withStakedToMe(accountBalance)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        mockEntityIdFactory();

        final var node0InfoBefore = writableStakingInfoState.get(node0Id);

        writableAccountStore.put(account.copyBuilder()
                .tinybarBalance(accountBalance - HBARS_TO_TINYBARS)
                .stakedAccountId(ownerId)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());

        final var originalPayer = writableAccountStore.get(payerId);
        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // even though only payer account has changed, since staked to me of owner changes,
        // it will trigger reward for owner
        assertThat(rewards).hasSize(1).containsEntry(ownerId, 6600L);

        final var modifiedPayer = writableAccountStore.get(payerId);
        final var modifiedOwner = writableAccountStore.get(ownerId);

        assertThat(modifiedOwner.stakedToMe()).isEqualTo(ownerAccountBefore.stakedToMe() - HBARS_TO_TINYBARS);
        // stakePeriodStart is updated everytime when reward is applied
        assertThat(modifiedOwner.stakePeriodStart()).isEqualTo(stakePeriodStart - 1);

        assertThat(modifiedPayer.stakedToMe()).isEqualTo(originalPayer.stakedToMe());
        assertThat(modifiedPayer.stakePeriodStart()).isEqualTo(stakePeriodStart);

        final var node0InfoAfter = writableStakingInfoStore.get(node0Id.number());
        assertThat(node0InfoAfter.stakeToReward()).isEqualTo(node0InfoBefore.stakeToReward() - HBARS_TO_TINYBARS);
        assertThat(node0InfoAfter.unclaimedStakeRewardStart()).isZero();
    }

    @Test
    void redirectsRewardOfStakeeDeletedInInnerBatchTransactionToBeneficiary() {
        // Atomic-batch case of the deleted-stakee freeze, exercising the child-dispatch beneficiary fold. In an
        // atomic batch the stakee is deleted by an inner (BATCH_INNER) transaction, which does not pay staking
        // rewards and records its delete->beneficiary mapping only on its own child record builder. Reward
        // finalization runs once, on the parent batch, where the stakee's start-of-batch (original) value is
        // still non-deleted -- so it is (correctly) rediscovered as a reward receiver whose reward must be
        // redirected to the beneficiary. Without folding the child builder's mapping onto the parent (root)
        // builder, payRewardsIfPending throws IllegalStateException (parent has no beneficiary,
        // getNumberOfDeletedAccounts() == 0) and the batch is rolled back to a zero-fee FAIL_INVALID record.
        // Complements redirectsRewardForAccountDeletedInChildDispatch (a node staker deleted in a PRIOR txn):
        // here the deleted account is a node stakee with an indirect staker, deleted in the CURRENT txn, so it
        // also exercises the node-stake settlement path (its stake is withdrawn exactly once).
        final var accountBalance = 55L * HBARS_TO_TINYBARS;
        // The indirect staker (payer) stakes to the stakee (owner) and is touched in the batch.
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedToMe(0L)
                .withStakedAccountId(ownerId)
                .build();
        // The rewardable stakee is NOT deleted at the start of the batch; unlike the prior-transaction case, it
        // is deleted only by an inner transaction within this batch, so its original value is still non-deleted.
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(0L)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(node0Id.number())
                .withStakedToMe(accountBalance)
                .build();
        // transferAccount is the beneficiary the inner CryptoDelete redirected the stakee's balance to.
        addToState(
                Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore, transferAccountId, transferAccount));

        mockEntityIdFactory();

        final var node0InfoBefore = writableStakingInfoState.get(node0Id);

        // The staker sends 1 hbar, which rediscovers the (still non-deleted at start-of-batch) stakee.
        writableAccountStore.put(account.copyBuilder()
                .tinybarBalance(accountBalance - HBARS_TO_TINYBARS)
                .stakedAccountId(ownerId)
                .build());
        // The inner transaction marked the stakee deleted (its remaining balance moved to the beneficiary).
        writableAccountStore.put(ownerAccountBefore
                .copyBuilder()
                .deleted(true)
                .tinybarBalance(0L)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart + 2)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());

        // The parent (root) builder starts WITHOUT any deleted-account beneficiary (the mapping is on the inner
        // delete's child builder). Back it with a real map so the fold -> redirect chain is exercised for real.
        final Map<AccountID, AccountID> rootBeneficiaries = new HashMap<>();
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        willAnswer(inv -> {
                    rootBeneficiaries.put(inv.getArgument(0), inv.getArgument(1));
                    return null;
                })
                .given(recordBuilder)
                .addBeneficiaryForDeletedAccount(any(AccountID.class), any(AccountID.class));
        given(recordBuilder.getNumberOfDeletedAccounts()).willAnswer(inv -> rootBeneficiaries.size());
        given(recordBuilder.getDeletedAccountBeneficiaryFor(any(AccountID.class)))
                .willAnswer(inv -> rootBeneficiaries.get(inv.<AccountID>getArgument(0)));

        // The inner (child) dispatch builder carries the delete->beneficiary mapping, exposed the same way the
        // production fold reads it -- via forEachDeletedAccountBeneficiary, as CryptoDelete records it.
        final var childRecordBuilder = mock(DeleteCapableTransactionStreamBuilder.class);
        willAnswer(inv -> {
                    final BiConsumer<AccountID, AccountID> action = inv.getArgument(0);
                    action.accept(ownerId, transferAccountId);
                    return null;
                })
                .given(childRecordBuilder)
                .forEachDeletedAccountBeneficiary(any());
        willAnswer(inv -> {
                    final Consumer<DeleteCapableTransactionStreamBuilder> consumer = inv.getArgument(1);
                    consumer.accept(childRecordBuilder);
                    return null;
                })
                .given(context)
                .forEachChildRecord(eq(DeleteCapableTransactionStreamBuilder.class), any());

        // Finalization must not throw (it used to fail with IllegalStateException), and the reward must be
        // redirected to the beneficiary exactly once -- never to the deleted stakee.
        final var rewards =
                assertDoesNotThrow(() -> subject.applyStakingRewards(context, Collections.emptySet(), emptyMap()));
        assertThat(rewards).hasSize(1).containsKey(transferAccountId).doesNotContainKey(ownerId);
        assertThat(rewards.get(transferAccountId)).isPositive();

        // The child dispatch's mapping was folded into the parent (root) builder.
        assertThat(rootBeneficiaries).containsEntry(ownerId, transferAccountId);

        // A batch has a single finalization, so the delete settles the stakee's node stake exactly once --
        // withdrawn, and not re-awarded because the account is deleted -- exactly as a standalone CryptoDelete
        // does; the indirect-staker touch adds no further change. So stakeToReward drops by precisely the
        // stakee's totalStake: neither left stale (no withdrawal) nor drained twice. That a LATER transaction
        // touching the orphaned staker must not re-withdraw is covered by
        // doesNotWithdrawNodeStakeForStakeeDeletedInPriorTransaction.
        final var node0InfoAfter = writableStakingInfoStore.get(node0Id.number());
        assertThat(node0InfoAfter.stakeToReward())
                .isEqualTo(node0InfoBefore.stakeToReward() - roundedToHbar(totalStake(ownerAccountBefore)));
    }

    @Test
    void doesNotRewardStakeeDeletedInPriorTransaction() {
        // Regression for the deleted-stakee freeze. The staker (payer) stakes to an account (owner)
        // that was deleted in a PRIOR transaction but still carries its stakedNodeId/stakedToMe.
        // Touching the staker must not rediscover the deleted stakee as a reward receiver; doing so
        // used to throw IllegalStateException (its reward could not be redirected to a beneficiary,
        // since that mapping only exists on the deleting transaction's record builder).
        final var accountBalance = 55L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart - 2)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedToMe(0L)
                .withStakedAccountId(ownerId)
                .build();
        // The stakee is already deleted (deleted in an earlier transaction), but its staking fields
        // were left intact by CryptoDelete, so without the guard it would be rediscovered here.
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(0L)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart - 2)
                .withDeclineReward(false)
                .withDeleted(true)
                .withStakedNodeId(node0Id.number())
                .withStakedToMe(accountBalance)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        mockEntityIdFactory();

        // The staker's balance changes (it sends 1 hbar), which previously triggered rediscovery of
        // the deleted stakee.
        writableAccountStore.put(account.copyBuilder()
                .tinybarBalance(accountBalance - HBARS_TO_TINYBARS)
                .stakedAccountId(ownerId)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());

        // Finalization must not throw (it used to fail with IllegalStateException because the reward
        // could not be redirected), and the deleted stakee must not be rewarded.
        final var rewards =
                assertDoesNotThrow(() -> subject.applyStakingRewards(context, Collections.emptySet(), emptyMap()));
        assertThat(rewards).doesNotContainKey(ownerId);
    }

    @Test
    void doesNotRewardDeletedExplicitRewardReceiver() {
        // Defense-in-depth companion to the guard above. A stakee deleted in a prior transaction that is
        // surfaced through the explicit reward-receiver path (StakingRewardsHelper#isCurrentlyStakedToNode,
        // which the contract service can feed) must also be skipped, so finalization neither throws nor
        // rewards it.
        final var accountBalance = 55L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart - 2)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedToMe(0L)
                .withStakedAccountId(ownerId)
                .build();
        // The stakee is already deleted (deleted in an earlier transaction) but still staked to a node.
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(0L)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart - 2)
                .withDeclineReward(false)
                .withDeleted(true)
                .withStakedNodeId(node0Id.number())
                .withStakedToMe(accountBalance)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        mockEntityIdFactory();

        writableAccountStore.put(account.copyBuilder()
                .tinybarBalance(accountBalance - HBARS_TO_TINYBARS)
                .stakedAccountId(ownerId)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());

        // The deleted stakee is passed explicitly (as the contract service would); it must still be
        // filtered out before reward distribution.
        final var rewards = assertDoesNotThrow(() -> subject.applyStakingRewards(context, Set.of(ownerId), emptyMap()));
        assertThat(rewards).doesNotContainKey(ownerId);
    }

    @Test
    void doesNotWithdrawNodeStakeForStakeeDeletedInPriorTransaction() {
        // Regression for the node-stake drift adjacent to the deleted-stakee freeze fix. The staker
        // (payer) stakes to an account (owner) that was deleted in a PRIOR transaction. The owner's
        // node stake was already settled when it was deleted, but its stale stakedToMe is still updated
        // when the staker's balance changes, pulling the deleted owner back into the modification set.
        // adjustStakeMetadata then withdrew the owner's entire (stale) stakedToMe from the node WITHOUT
        // re-awarding (the award path is guarded by !modifiedAccount.deleted()), spuriously reducing the
        // node's stakeToReward on every transaction the orphaned staker makes and masking the rewardable
        // stake of the node's other stakers. The node's stake must be left untouched.
        final var accountBalance = 55L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart - 2)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedToMe(0L)
                .withStakedAccountId(ownerId)
                .build();
        // The stakee was deleted in an earlier transaction; CryptoDelete left its staking fields intact.
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(0L)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart - 2)
                .withDeclineReward(false)
                .withDeleted(true)
                .withStakedNodeId(node0Id.number())
                .withStakedToMe(accountBalance)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        mockEntityIdFactory();

        final var node0InfoBefore = writableStakingInfoState.get(node0Id);

        // The staker sends 1 hbar, which updates the deleted stakee's stale stakedToMe.
        writableAccountStore.put(account.copyBuilder()
                .tinybarBalance(accountBalance - HBARS_TO_TINYBARS)
                .stakedAccountId(ownerId)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());

        subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // The deleted stakee's node stake was already withdrawn at delete time; the later transaction
        // must not withdraw it again. Before the fix, node0's stakeToReward dropped by the stale
        // stakedToMe (accountBalance), i.e. 666 hbar -> 611 hbar.
        final var node0InfoAfter = writableStakingInfoStore.get(node0Id.number());
        assertThat(node0InfoAfter.stakeToReward()).isEqualTo(node0InfoBefore.stakeToReward());
    }

    @Test
    void doesntUpdateStakedToMeIfStakerBalanceIsExactlyTheSame() {
        final var accountBalance = 55L * HBARS_TO_TINYBARS;
        final var ownerBalance = 11L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart - 2)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedToMe(0L)
                .withStakedAccountId(ownerId)
                .build();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart - 2)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(node0Id.number())
                .withStakedToMe(accountBalance)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        final var node0InfoBefore = writableStakingInfoState.get(node0Id);

        // Just change 800 balance
        writableAccountStore.put(stakingRewardAccount
                .copyBuilder()
                .tinybarBalance(stakingRewardAccount.tinybarBalance() + HBARS_TO_TINYBARS)
                .build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);

        final var originalPayer = writableAccountStore.get(payerId);

        // This should not change anything
        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // No rewards should be paid
        assertThat(rewards).isEmpty();

        // assert nothing changed in account and node
        final var modifiedPayer = writableAccountStore.get(payerId);
        final var modifiedOwner = writableAccountStore.get(ownerId);
        final var node0InfoAfter = writableStakingInfoStore.get(0L);

        assertThat(modifiedOwner.stakedToMe()).isEqualTo(ownerAccountBefore.stakedToMe());
        // stakePeriodStart is updated only when reward is applied
        assertThat(modifiedOwner.stakePeriodStart()).isEqualTo(stakePeriodStart - 2);

        assertThat(modifiedPayer.stakedToMe()).isEqualTo(originalPayer.stakedToMe());
        assertThat(modifiedPayer.stakePeriodStart()).isEqualTo(stakePeriodStart - 2);

        assertThat(node0InfoAfter.stakeToReward()).isEqualTo(node0InfoBefore.stakeToReward());
        assertThat(node0InfoAfter.unclaimedStakeRewardStart()).isZero();
    }

    @Test
    void stakePeriodStartUpdatedWhenStakedToAccount() {
        final var accountBalance = 55L * HBARS_TO_TINYBARS;
        final var ownerBalance = 11L * HBARS_TO_TINYBARS;
        final var payerAccountBefore = new AccountCustomizer()
                .withAccount(account)
                .withBalance(accountBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart - 2)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedToMe(0L)
                .withStakedAccountId(ownerId)
                .build();
        mockEntityIdFactory();
        final var ownerAccountBefore = new AccountCustomizer()
                .withAccount(ownerAccount)
                .withBalance(ownerBalance)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(stakePeriodStart - 2)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(node0Id.number())
                .withStakedToMe(accountBalance)
                .build();
        addToState(Map.of(payerId, payerAccountBefore, ownerId, ownerAccountBefore));

        writableAccountStore.put(
                account.copyBuilder().stakedAccountId(stakingRewardId).build());

        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(stakePeriodStart)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());

        final var originalPayer = writableAccountStore.get(payerId);
        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        assertThat(rewards).hasSize(1).containsEntry(ownerId, 6600L);

        final var modifiedPayer = writableAccountStore.get(payerId);
        final var modifiedOwner = writableAccountStore.get(ownerId);
        // Since payer is staked to reward account now, its balance should not add to stakedToMe of owner
        assertThat(modifiedOwner.stakedToMe()).isZero();
        // stakePeriodStart is updated everytime when reward is applied
        assertThat(modifiedOwner.stakePeriodStart()).isEqualTo(stakePeriodStart - 1);
        // stakePeriodStart is not updated here
        assertThat(modifiedPayer.stakedToMe()).isEqualTo(originalPayer.stakedToMe());
        assertThat(modifiedPayer.stakePeriodStart()).isEqualTo(stakePeriodStart);
    }

    // --- Settlement of an account whose stakedToMe rises only because of a redirected reward ---
    //
    // A deleted earner's reward is paid to its beneficiary. When that beneficiary stakes to a third
    // account, crediting it raises the third account's stakedToMe. The reward-receiver snapshot is
    // taken before any reward is paid, so the third account was never in a reward situation and its
    // stake metadata stayed put -- which let its still-open reward periods be repriced at the new,
    // larger stake. These tests pin the settlement that now happens before the stake goes up.

    /**
     * Per-hbar reward for exactly one open period, from the node reward sum history
     * ({@code [300, 200, 100]}) of the test fixture.
     */
    private static final long ONE_PERIOD_RATE_PER_HBAR = 100L;

    @Test
    void redirectedRewardSettlesTheBeneficiarysStakeeBeforeRaisingItsStakedToMe() {
        final var currentStakePeriod = stakePeriodStart + 2;
        final var earnerPrincipal = 7L * HBARS_TO_TINYBARS;
        // The earner's reward comes almost entirely from its indirect stake, so it can be drained to
        // zero and still owe a reward well above one whole hbar -- enough to move a stakee's stake.
        final var earnerIndirectStake = 2_000_000L * HBARS_TO_TINYBARS;
        final var aggregatorBalance = 111L * HBARS_TO_TINYBARS;
        final var fundingBalance = 1_000L * HBARS_TO_TINYBARS;
        final var expectedRedirect =
                ((earnerPrincipal + earnerIndirectStake) / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;
        final var expectedAggregatorReward = (aggregatorBalance / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;

        givenRedirectToAccountStakedBeneficiary(
                currentStakePeriod, earnerPrincipal, earnerIndirectStake, aggregatorBalance, fundingBalance, b -> b);

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // The redirect itself is unchanged, and the aggregator is now assessed in the same
        // transaction for exactly what it had accrued on its own, pre-redirect stake.
        assertThat(rewards)
                .hasSize(2)
                .containsEntry(transferAccountId, expectedRedirect)
                .containsEntry(ownerId, expectedAggregatorReward);

        final var beneficiary = writableAccountStore.get(transferAccountId);
        final var aggregator = writableAccountStore.get(ownerId);
        assertThat(beneficiary.tinybarBalance()).isEqualTo(expectedRedirect);
        assertThat(aggregator.tinybarBalance()).isEqualTo(aggregatorBalance + expectedAggregatorReward);

        // The stake really did go up, so the settlement had to happen before the bump.
        assertThat(aggregator.stakedToMe())
                .isEqualTo(roundedToHbar(expectedRedirect))
                .isPositive();

        // Metadata advanced, and remembers the stake from BEFORE the redirect.
        assertThat(aggregator.stakePeriodStart()).isEqualTo(currentStakePeriod - 1);
        assertThat(aggregator.stakeAtStartOfLastRewardedPeriod()).isEqualTo(aggregatorBalance);

        // The funding account paid for both, once.
        assertThat(writableAccountStore.get(stakingRewardId).tinybarBalance())
                .isEqualTo(fundingBalance - expectedRedirect - expectedAggregatorReward);

        // Next period the aggregator is priced on the stake it held before the redirect, not on the
        // inflated total stake it holds now -- so the added stake earns nothing retroactively.
        final var nextPeriodInstant = LocalDate.ofEpochDay(currentStakePeriod + 1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        stakePeriodManager.setCurrentStakePeriodFor(nextPeriodInstant);
        assertThat(stakeRewardCalculator.computePendingReward(
                        aggregator, writableStakingInfoStore, readableRewardsStore, nextPeriodInstant))
                .isEqualTo(expectedAggregatorReward);
    }

    @Test
    void settlesTheStakeeOnceWhenTwoRedirectsLandOnAccountsStakingToIt() {
        final var currentStakePeriod = stakePeriodStart + 2;
        final var earnerPrincipal = 7L * HBARS_TO_TINYBARS;
        final var earnerIndirectStake = 2_000_000L * HBARS_TO_TINYBARS;
        final var aggregatorBalance = 111L * HBARS_TO_TINYBARS;
        final var fundingBalance = 1_000L * HBARS_TO_TINYBARS;
        final var expectedRedirect =
                ((earnerPrincipal + earnerIndirectStake) / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;
        final var expectedAggregatorReward = (aggregatorBalance / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;

        final var earnerBefore = stakedToNode(account, earnerPrincipal, earnerIndirectStake, stakePeriodStart);
        final var secondEarnerBefore =
                stakedToNode(treasuryAccount, earnerPrincipal, earnerIndirectStake, stakePeriodStart);
        final var beneficiaryBefore = stakedToAggregator(transferAccount, currentStakePeriod);
        final var secondBeneficiaryBefore = stakedToAggregator(spenderAccount, currentStakePeriod);
        final var aggregatorBefore = stakedToNode(ownerAccount, aggregatorBalance, 0L, stakePeriodStart);
        addToState(Map.of(
                payerId, earnerBefore,
                treasuryId, secondEarnerBefore,
                transferAccountId, beneficiaryBefore,
                spenderId, secondBeneficiaryBefore,
                ownerId, aggregatorBefore));

        // Both earners are drained and deleted; neither delete moves any tinybars, so neither
        // beneficiary's balance changes and the aggregator stays out of the initial snapshot.
        writableAccountStore.put(
                earnerBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(
                secondEarnerBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(beneficiaryBefore);
        writableAccountStore.put(secondBeneficiaryBefore);
        givenFundedRewardAccountAt(currentStakePeriod, fundingBalance);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(2);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(transferAccountId);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(treasuryId)).willReturn(spenderId);

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // Two redirects, but the shared stakee is assessed -- and paid -- exactly once.
        assertThat(rewards)
                .hasSize(3)
                .containsEntry(transferAccountId, expectedRedirect)
                .containsEntry(spenderId, expectedRedirect)
                .containsEntry(ownerId, expectedAggregatorReward);
        final var aggregator = writableAccountStore.get(ownerId);
        assertThat(aggregator.tinybarBalance()).isEqualTo(aggregatorBalance + expectedAggregatorReward);
        assertThat(aggregator.stakedToMe()).isEqualTo(2 * roundedToHbar(expectedRedirect));
        assertThat(writableAccountStore.get(stakingRewardId).tinybarBalance())
                .isEqualTo(fundingBalance - 2 * expectedRedirect - expectedAggregatorReward);
    }

    @Test
    void settlementOfTheRedirectStakeeCannotOverrunTheFundingBalance() {
        final var currentStakePeriod = stakePeriodStart + 2;
        final var earnerPrincipal = 7L * HBARS_TO_TINYBARS;
        final var earnerIndirectStake = 2_000_000L * HBARS_TO_TINYBARS;
        final var aggregatorBalance = 111L * HBARS_TO_TINYBARS;
        final var expectedRedirect =
                ((earnerPrincipal + earnerIndirectStake) / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;
        final var expectedAggregatorReward = (aggregatorBalance / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;
        // One tinybar short of covering both the redirect and the stakee's own reward.
        final var fundingBalance = expectedRedirect + expectedAggregatorReward - 1;

        givenRedirectToAccountStakedBeneficiary(
                currentStakePeriod, earnerPrincipal, earnerIndirectStake, aggregatorBalance, fundingBalance, b -> b);

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // The settlement draws on what the redirect left behind, so the two together never pay out
        // more than the funding account held.
        assertThat(rewards)
                .hasSize(2)
                .containsEntry(transferAccountId, expectedRedirect)
                .containsEntry(ownerId, expectedAggregatorReward - 1);
        assertThat(writableAccountStore.get(stakingRewardId).tinybarBalance()).isZero();
    }

    @Test
    void doesNotAdvanceMetadataOfARedirectStakeeNotRewardableOnItsOwnStake() {
        final var currentStakePeriod = stakePeriodStart + 2;
        final var earnerPrincipal = 7L * HBARS_TO_TINYBARS;
        final var earnerIndirectStake = 2_000_000L * HBARS_TO_TINYBARS;
        final var aggregatorBalance = 111L * HBARS_TO_TINYBARS;
        final var fundingBalance = 1_000L * HBARS_TO_TINYBARS;
        final var expectedRedirect =
                ((earnerPrincipal + earnerIndirectStake) / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;

        // The stakee only began staking in the current period, so it has no open reward period.
        givenRedirectToAccountStakedBeneficiary(
                currentStakePeriod,
                earnerPrincipal,
                earnerIndirectStake,
                aggregatorBalance,
                fundingBalance,
                b -> b.stakePeriodStart(currentStakePeriod));

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // It is assessed, earns nothing, and keeps the metadata of an account that has not yet been
        // rewarded -- assessing it must not make it look rewardable.
        assertThat(rewards).hasSize(2).containsEntry(transferAccountId, expectedRedirect);
        assertThat(rewards).containsEntry(ownerId, 0L);
        final var aggregator = writableAccountStore.get(ownerId);
        assertThat(aggregator.tinybarBalance()).isEqualTo(aggregatorBalance);
        assertThat(aggregator.stakePeriodStart()).isEqualTo(currentStakePeriod);
        assertThat(aggregator.stakeAtStartOfLastRewardedPeriod()).isEqualTo(-1L);
        assertThat(aggregator.stakedToMe()).isEqualTo(roundedToHbar(expectedRedirect));
        assertThat(writableAccountStore.get(stakingRewardId).tinybarBalance())
                .isEqualTo(fundingBalance - expectedRedirect);
    }

    @Test
    void redirectStakeeThatDeclinesRewardIsSettledWithZero() {
        final var currentStakePeriod = stakePeriodStart + 2;
        final var earnerPrincipal = 7L * HBARS_TO_TINYBARS;
        final var earnerIndirectStake = 2_000_000L * HBARS_TO_TINYBARS;
        final var aggregatorBalance = 111L * HBARS_TO_TINYBARS;
        final var fundingBalance = 1_000L * HBARS_TO_TINYBARS;
        final var expectedRedirect =
                ((earnerPrincipal + earnerIndirectStake) / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;

        givenRedirectToAccountStakedBeneficiary(
                currentStakePeriod,
                earnerPrincipal,
                earnerIndirectStake,
                aggregatorBalance,
                fundingBalance,
                b -> b.declineReward(true));

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // A stakee that declines rewards is still assessed, but is paid nothing.
        assertThat(rewards).hasSize(2).containsEntry(transferAccountId, expectedRedirect);
        assertThat(rewards).containsEntry(ownerId, 0L);
        final var aggregator = writableAccountStore.get(ownerId);
        assertThat(aggregator.tinybarBalance()).isEqualTo(aggregatorBalance);
        assertThat(aggregator.stakedToMe()).isEqualTo(roundedToHbar(expectedRedirect));
        assertThat(writableAccountStore.get(stakingRewardId).tinybarBalance())
                .isEqualTo(fundingBalance - expectedRedirect);
    }

    @Test
    void assessesNobodyExtraWhenTheRedirectStakeeIsItselfStakedToAnAccount() {
        final var currentStakePeriod = stakePeriodStart + 2;
        final var earnerPrincipal = 7L * HBARS_TO_TINYBARS;
        final var earnerIndirectStake = 2_000_000L * HBARS_TO_TINYBARS;
        final var fundingBalance = 1_000L * HBARS_TO_TINYBARS;
        final var expectedRedirect =
                ((earnerPrincipal + earnerIndirectStake) / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;

        final var earnerBefore = stakedToNode(account, earnerPrincipal, earnerIndirectStake, stakePeriodStart);
        final var beneficiaryBefore = stakedToAggregator(transferAccount, currentStakePeriod);
        // The stakee delegates onward instead of staking to a node, so no reward can reach it and
        // the search for newly affected stakees stops here.
        final var aggregatorBefore = ownerAccount
                .copyBuilder()
                .tinybarBalance(111L * HBARS_TO_TINYBARS)
                .stakedAccountId(treasuryId)
                .stakedToMe(0L)
                .stakePeriodStart(stakePeriodStart)
                .stakeAtStartOfLastRewardedPeriod(-1L)
                .declineReward(false)
                .deleted(false)
                .build();
        addToState(Map.of(payerId, earnerBefore, transferAccountId, beneficiaryBefore, ownerId, aggregatorBefore));

        writableAccountStore.put(
                earnerBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(beneficiaryBefore);
        givenFundedRewardAccountAt(currentStakePeriod, fundingBalance);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(1);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(transferAccountId);

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        assertThat(rewards).hasSize(1).containsEntry(transferAccountId, expectedRedirect);
        assertThat(writableAccountStore.get(stakingRewardId).tinybarBalance())
                .isEqualTo(fundingBalance - expectedRedirect);
    }

    @Test
    void settlementDoesNotReassessABeneficiaryTheDistributorAlreadyAssessed() {
        // The distributor assesses beneficiaries it enqueues itself, and those never appear in the
        // reward-receiver set the handler passed in. If the settlement pass seeded its
        // already-assessed set from that input set alone, such a beneficiary could be assessed a
        // second time here and paid its own reward twice.
        //
        // Both paths meet in this transaction: a deleted earner redirects to an ACCOUNT-staked
        // recipient (so the settlement pass discovers that recipient's stakee), and a second
        // deleted earner redirects to that same stakee, which is node-staked (so the distributor
        // enqueues and assesses it). The stakee must end up assessed exactly once.
        final var currentStakePeriod = stakePeriodStart + 2;
        final var earnerPrincipal = 7L * HBARS_TO_TINYBARS;
        final var earnerIndirectStake = 2_000_000L * HBARS_TO_TINYBARS;
        final var secondEarnerBalance = 5L * HBARS_TO_TINYBARS;
        final var stakeeBalance = 111L * HBARS_TO_TINYBARS;
        final var fundingBalance = 1_000L * HBARS_TO_TINYBARS;
        final var bigRedirect =
                ((earnerPrincipal + earnerIndirectStake) / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;
        final var smallRedirect = (secondEarnerBalance / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;
        final var stakeeOwnReward = (stakeeBalance / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;

        final var earnerBefore = stakedToNode(account, earnerPrincipal, earnerIndirectStake, stakePeriodStart);
        final var secondEarnerBefore = stakedToNode(treasuryAccount, secondEarnerBalance, 0L, stakePeriodStart);
        // The account-staked recipient of the first redirect; its credit is what makes the
        // settlement pass discover the stakee below.
        final var recipientBefore = stakedToAggregator(transferAccount, currentStakePeriod);
        final var stakeeBefore = stakedToNode(ownerAccount, stakeeBalance, 0L, stakePeriodStart);
        addToState(Map.of(
                payerId, earnerBefore,
                treasuryId, secondEarnerBefore,
                transferAccountId, recipientBefore,
                ownerId, stakeeBefore));

        writableAccountStore.put(
                earnerBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(
                secondEarnerBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(recipientBefore);
        givenFundedRewardAccountAt(currentStakePeriod, fundingBalance);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(2);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(transferAccountId);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(treasuryId)).willReturn(ownerId);

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // The stakee's entry is the second redirect plus its own reward, counted once. Assessing it
        // twice would add another stakeeOwnReward here.
        assertThat(rewards)
                .hasSize(2)
                .containsEntry(transferAccountId, bigRedirect)
                .containsEntry(ownerId, smallRedirect + stakeeOwnReward);

        final var stakee = writableAccountStore.get(ownerId);
        assertThat(stakee.tinybarBalance()).isEqualTo(stakeeBalance + smallRedirect + stakeeOwnReward);
        // It was assessed on its own stake, so its metadata advanced exactly as for any earner.
        assertThat(stakee.stakePeriodStart()).isEqualTo(currentStakePeriod - 1);
        assertThat(stakee.stakeAtStartOfLastRewardedPeriod()).isEqualTo(stakeeBalance);
        // And it did gain the stake the first redirect produced.
        assertThat(stakee.stakedToMe()).isEqualTo(roundedToHbar(bigRedirect));
        assertThat(writableAccountStore.get(stakingRewardId).tinybarBalance())
                .isEqualTo(fundingBalance - bigRedirect - smallRedirect - stakeeOwnReward);
    }

    // --- The two fixes in this PR acting in one transaction ---
    //
    // The #1642 fix assesses a node-staked beneficiary the distributor enqueues for itself; the
    // BB-93847 fix assesses the stakee of an account-staked recipient before its stake is raised.
    // These tests pin that the two do not overlap, do not suppress each other, and draw on one
    // shared funding budget. See also
    // settlementDoesNotReassessABeneficiaryTheDistributorAlreadyAssessed, which covers the case
    // where both mechanisms target the SAME account.

    @Test
    void bothFixesActOnDifferentAccountsInOneTransaction() {
        final var currentStakePeriod = stakePeriodStart + 2;
        final var bigEarnerPrincipal = 7L * HBARS_TO_TINYBARS;
        final var bigEarnerIndirectStake = 2_000_000L * HBARS_TO_TINYBARS;
        final var smallEarnerBalance = 5L * HBARS_TO_TINYBARS;
        final var stakeeBalance = 111L * HBARS_TO_TINYBARS;
        final var nodeBeneficiaryBalance = 50L * HBARS_TO_TINYBARS;
        final var fundingBalance = 1_000L * HBARS_TO_TINYBARS;

        final var bigRedirect =
                ((bigEarnerPrincipal + bigEarnerIndirectStake) / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;
        final var smallRedirect = (smallEarnerBalance / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;
        final var stakeeOwnReward = (stakeeBalance / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;
        final var nodeBeneficiaryOwnReward = (nodeBeneficiaryBalance / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;

        givenTwoEarnersRedirectingToBothKindsOfBeneficiary(
                currentStakePeriod,
                bigEarnerPrincipal,
                bigEarnerIndirectStake,
                smallEarnerBalance,
                stakeeBalance,
                nodeBeneficiaryBalance,
                fundingBalance);

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // Three recipients, each credited exactly what it is owed: the account-staked recipient
        // gets only its redirect, the node-staked beneficiary gets its redirect plus its own
        // reward (#1642), and the stakee gets its own reward (BB-93847).
        assertThat(rewards)
                .hasSize(3)
                .containsEntry(transferAccountId, bigRedirect)
                .containsEntry(spenderId, smallRedirect + nodeBeneficiaryOwnReward)
                .containsEntry(ownerId, stakeeOwnReward);

        final var accountStakedRecipient = writableAccountStore.get(transferAccountId);
        final var nodeStakedBeneficiary = writableAccountStore.get(spenderId);
        final var stakee = writableAccountStore.get(ownerId);

        assertThat(accountStakedRecipient.tinybarBalance()).isEqualTo(bigRedirect);
        assertThat(nodeStakedBeneficiary.tinybarBalance())
                .isEqualTo(nodeBeneficiaryBalance + smallRedirect + nodeBeneficiaryOwnReward);
        assertThat(stakee.tinybarBalance()).isEqualTo(stakeeBalance + stakeeOwnReward);

        // Both assessed accounts advanced their own metadata, each remembering its own
        // pre-transaction stake -- neither mechanism wrote over the other's bookkeeping.
        assertThat(nodeStakedBeneficiary.stakePeriodStart()).isEqualTo(currentStakePeriod - 1);
        assertThat(nodeStakedBeneficiary.stakeAtStartOfLastRewardedPeriod()).isEqualTo(nodeBeneficiaryBalance);
        assertThat(stakee.stakePeriodStart()).isEqualTo(currentStakePeriod - 1);
        assertThat(stakee.stakeAtStartOfLastRewardedPeriod()).isEqualTo(stakeeBalance);

        // The stakee really did gain the stake the redirect produced.
        assertThat(stakee.stakedToMe()).isEqualTo(roundedToHbar(bigRedirect));

        // The funding account paid for all four assessments once each.
        assertThat(writableAccountStore.get(stakingRewardId).tinybarBalance())
                .isEqualTo(fundingBalance - bigRedirect - smallRedirect - nodeBeneficiaryOwnReward - stakeeOwnReward);
    }

    @Test
    void bothFixesShareOneFundingBudget() {
        final var currentStakePeriod = stakePeriodStart + 2;
        final var bigEarnerPrincipal = 7L * HBARS_TO_TINYBARS;
        final var bigEarnerIndirectStake = 2_000_000L * HBARS_TO_TINYBARS;
        final var smallEarnerBalance = 5L * HBARS_TO_TINYBARS;
        final var stakeeBalance = 111L * HBARS_TO_TINYBARS;
        final var nodeBeneficiaryBalance = 50L * HBARS_TO_TINYBARS;

        final var bigRedirect =
                ((bigEarnerPrincipal + bigEarnerIndirectStake) / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;
        final var smallRedirect = (smallEarnerBalance / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;
        final var stakeeOwnReward = (stakeeBalance / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;
        final var nodeBeneficiaryOwnReward = (nodeBeneficiaryBalance / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;
        // One tinybar short of covering everything both mechanisms want to pay.
        final var fundingBalance = bigRedirect + smallRedirect + nodeBeneficiaryOwnReward + stakeeOwnReward - 1;

        givenTwoEarnersRedirectingToBothKindsOfBeneficiary(
                currentStakePeriod,
                bigEarnerPrincipal,
                bigEarnerIndirectStake,
                smallEarnerBalance,
                stakeeBalance,
                nodeBeneficiaryBalance,
                fundingBalance);

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // The settlement pass runs last and sees a funding account the first distribution already
        // reduced, so the shortfall lands on it instead of overdrawing the account.
        assertThat(rewards)
                .hasSize(3)
                .containsEntry(transferAccountId, bigRedirect)
                .containsEntry(spenderId, smallRedirect + nodeBeneficiaryOwnReward)
                .containsEntry(ownerId, stakeeOwnReward - 1);
        assertThat(writableAccountStore.get(stakingRewardId).tinybarBalance()).isZero();
    }

    @Test
    void settlementRoundStillEnqueuesItsOwnRedirectBeneficiary() {
        // The deepest composition: the stakee that the BB-93847 pass discovers is itself deleted in
        // this transaction, so paying it redirects again -- and the #1642 work queue must run
        // inside that settlement round to assess the new, node-staked beneficiary for its own
        // reward. The beneficiary's metadata may only advance from its own self-received
        // assessment, never from the redirect that arrived with it.
        final var currentStakePeriod = stakePeriodStart + 2;
        final var earnerPrincipal = 7L * HBARS_TO_TINYBARS;
        final var earnerIndirectStake = 2_000_000L * HBARS_TO_TINYBARS;
        final var deletedStakeeBalance = 111L * HBARS_TO_TINYBARS;
        final var finalBeneficiaryBalance = 50L * HBARS_TO_TINYBARS;
        final var fundingBalance = 1_000L * HBARS_TO_TINYBARS;

        final var bigRedirect =
                ((earnerPrincipal + earnerIndirectStake) / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;
        final var deletedStakeeReward = (deletedStakeeBalance / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;
        final var finalBeneficiaryOwnReward = (finalBeneficiaryBalance / HBARS_TO_TINYBARS) * ONE_PERIOD_RATE_PER_HBAR;

        final var earnerBefore = stakedToNode(account, earnerPrincipal, earnerIndirectStake, stakePeriodStart);
        final var recipientBefore = stakedToAccount(transferAccount, ownerId, currentStakePeriod);
        final var deletedStakeeBefore = stakedToNode(ownerAccount, deletedStakeeBalance, 0L, stakePeriodStart);
        final var finalBeneficiaryBefore = stakedToNode(spenderAccount, finalBeneficiaryBalance, 0L, stakePeriodStart);
        addToState(Map.of(
                payerId, earnerBefore,
                transferAccountId, recipientBefore,
                ownerId, deletedStakeeBefore,
                spenderId, finalBeneficiaryBefore));

        writableAccountStore.put(
                earnerBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(recipientBefore);
        // The stakee is deleted without moving any tinybars, so it never enters the initial
        // reward-receiver set; only the settlement pass reaches it.
        writableAccountStore.put(deletedStakeeBefore.copyBuilder().deleted(true).build());
        givenFundedRewardAccountAt(currentStakePeriod, fundingBalance);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(2);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(transferAccountId);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(ownerId)).willReturn(spenderId);

        final var rewards = subject.applyStakingRewards(context, Collections.emptySet(), emptyMap());

        // The deleted stakee's own reward is redirected onward, and the account it lands on is
        // assessed for its own reward in the same round.
        assertThat(rewards)
                .hasSize(2)
                .containsEntry(transferAccountId, bigRedirect)
                .containsEntry(spenderId, deletedStakeeReward + finalBeneficiaryOwnReward);

        final var finalBeneficiary = writableAccountStore.get(spenderId);
        assertThat(finalBeneficiary.tinybarBalance())
                .isEqualTo(finalBeneficiaryBalance + deletedStakeeReward + finalBeneficiaryOwnReward);
        // Advanced from its OWN assessment, and remembering its own pre-transaction stake.
        assertThat(finalBeneficiary.stakePeriodStart()).isEqualTo(currentStakePeriod - 1);
        assertThat(finalBeneficiary.stakeAtStartOfLastRewardedPeriod()).isEqualTo(finalBeneficiaryBalance);

        // The deleted stakee was paid through its beneficiary, so nothing advances for it.
        final var deletedStakee = writableAccountStore.get(ownerId);
        assertThat(deletedStakee.deleted()).isTrue();
        assertThat(deletedStakee.stakePeriodStart()).isEqualTo(stakePeriodStart);
        assertThat(deletedStakee.stakeAtStartOfLastRewardedPeriod()).isEqualTo(-1L);

        assertThat(writableAccountStore.get(stakingRewardId).tinybarBalance())
                .isEqualTo(fundingBalance - bigRedirect - deletedStakeeReward - finalBeneficiaryOwnReward);
    }

    /**
     * Builds one transaction that triggers both fixes on different accounts: a big earner whose
     * redirect lands on an ACCOUNT-staked recipient (so the settlement pass must assess that
     * recipient's stakee), and a small earner whose redirect lands on an untouched NODE-staked
     * beneficiary (so the distributor must enqueue and assess that beneficiary). Neither delete
     * moves any tinybars.
     */
    private void givenTwoEarnersRedirectingToBothKindsOfBeneficiary(
            final long currentStakePeriod,
            final long bigEarnerPrincipal,
            final long bigEarnerIndirectStake,
            final long smallEarnerBalance,
            final long stakeeBalance,
            final long nodeBeneficiaryBalance,
            final long fundingBalance) {
        final var bigEarnerBefore = stakedToNode(account, bigEarnerPrincipal, bigEarnerIndirectStake, stakePeriodStart);
        final var smallEarnerBefore = stakedToNode(treasuryAccount, smallEarnerBalance, 0L, stakePeriodStart);
        final var accountStakedRecipientBefore = stakedToAccount(transferAccount, ownerId, currentStakePeriod);
        final var stakeeBefore = stakedToNode(ownerAccount, stakeeBalance, 0L, stakePeriodStart);
        final var nodeStakedBeneficiaryBefore =
                stakedToNode(spenderAccount, nodeBeneficiaryBalance, 0L, stakePeriodStart);
        addToState(Map.of(
                payerId, bigEarnerBefore,
                treasuryId, smallEarnerBefore,
                transferAccountId, accountStakedRecipientBefore,
                ownerId, stakeeBefore,
                spenderId, nodeStakedBeneficiaryBefore));

        writableAccountStore.put(
                bigEarnerBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(
                smallEarnerBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        writableAccountStore.put(accountStakedRecipientBefore);
        givenFundedRewardAccountAt(currentStakePeriod, fundingBalance);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(2);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(transferAccountId);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(treasuryId)).willReturn(spenderId);
    }

    /**
     * Builds the drain-then-delete scenario: a node-staked earner with a large indirect stake is
     * emptied and deleted, naming a beneficiary that stakes to a node-staked aggregator. The delete
     * moves no tinybars, so the aggregator is not in the initial reward-receiver snapshot.
     *
     * @param currentStakePeriod the period the transaction is handled in
     * @param earnerPrincipal the earner's balance before it is drained
     * @param earnerIndirectStake the stake delegated to the earner
     * @param aggregatorBalance the aggregator's balance
     * @param fundingBalance the balance of the staking reward account
     * @param aggregatorSpec applied to the aggregator, to vary the case under test
     */
    private void givenRedirectToAccountStakedBeneficiary(
            final long currentStakePeriod,
            final long earnerPrincipal,
            final long earnerIndirectStake,
            final long aggregatorBalance,
            final long fundingBalance,
            @NonNull final java.util.function.UnaryOperator<Account.Builder> aggregatorSpec) {
        final var earnerBefore = stakedToNode(account, earnerPrincipal, earnerIndirectStake, stakePeriodStart);
        final var beneficiaryBefore = stakedToAggregator(transferAccount, currentStakePeriod);
        final var aggregatorBefore = aggregatorSpec
                .apply(stakedToNode(ownerAccount, aggregatorBalance, 0L, stakePeriodStart)
                        .copyBuilder())
                .build();
        addToState(Map.of(payerId, earnerBefore, transferAccountId, beneficiaryBefore, ownerId, aggregatorBefore));

        // The earner is drained to zero and deleted in the same transaction, so at finalization it
        // still owes its reward but has nothing left to transfer to the beneficiary.
        writableAccountStore.put(
                earnerBefore.copyBuilder().tinybarBalance(0).deleted(true).build());
        // The delete touches the beneficiary without changing its balance.
        writableAccountStore.put(beneficiaryBefore);
        givenFundedRewardAccountAt(currentStakePeriod, fundingBalance);
        given(recordBuilder.getNumberOfDeletedAccounts()).willReturn(1);
        given(recordBuilder.getDeletedAccountBeneficiaryFor(payerId)).willReturn(transferAccountId);
    }

    private Account stakedToNode(
            @NonNull final Account template,
            final long balance,
            final long stakedToMe,
            final long accountStakePeriodStart) {
        return new AccountCustomizer()
                .withAccount(template)
                .withBalance(balance)
                .withStakedToMe(stakedToMe)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(accountStakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedNodeId(node0Id.number())
                .build();
    }

    private Account stakedToAggregator(@NonNull final Account template, final long accountStakePeriodStart) {
        return stakedToAccount(template, ownerId, accountStakePeriodStart);
    }

    private Account stakedToAccount(
            @NonNull final Account template, @NonNull final AccountID stakeeId, final long accountStakePeriodStart) {
        return new AccountCustomizer()
                .withAccount(template)
                .withBalance(0L)
                .withStakedToMe(0L)
                .withStakeAtStartOfLastRewardPeriod(-1L)
                .withStakePeriodStart(accountStakePeriodStart)
                .withDeclineReward(false)
                .withDeleted(false)
                .withStakedAccountId(stakeeId)
                .build();
    }

    private void givenFundedRewardAccountAt(final long currentStakePeriod, final long fundingBalance) {
        writableAccountStore.put(Account.newBuilder()
                .accountId(stakingRewardId)
                .tinybarBalance(fundingBalance)
                .build());
        given(context.consensusTime())
                .willReturn(LocalDate.ofEpochDay(currentStakePeriod)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class))
                .willReturn(recordBuilder);
        stakePeriodManager.setCurrentStakePeriodFor(context.consensusTime());
        mockEntityIdFactory();
    }

    private void mockEntityIdFactory() {
        long stackingRewardAccount =
                configuration.getConfigData(AccountsConfig.class).stakingRewardAccount();
        final var hederaConfig = configuration.getConfigData(HederaConfig.class);
        given(entityIdFactory.newAccountId(stackingRewardAccount))
                .willReturn(AccountID.newBuilder()
                        .shardNum(hederaConfig.shard())
                        .realmNum(hederaConfig.realm())
                        .accountNum(stackingRewardAccount)
                        .build());
    }

    private void randomStakeNodeChanges() {
        writableAccountStore.put(account.copyBuilder()
                .tinybarBalance(100L)
                .stakedNodeId(0L)
                .declineReward(false)
                .build());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
    }

    private void noStakeChanges() {
        writableAccountStore.put(account.copyBuilder().key(kycKey).build());
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
    }

    private void addToState(Map<AccountID, Account> idsToAccounts) {
        final var readableBuilder = emptyReadableAccountStateBuilder().value(stakingRewardId, stakingRewardAccount);
        final var writableBuilder = emptyWritableAccountStateBuilder().value(stakingRewardId, stakingRewardAccount);
        for (var entry : idsToAccounts.entrySet()) {
            readableBuilder.value(entry.getKey(), entry.getValue());
            writableBuilder.value(entry.getKey(), entry.getValue());
        }
        readableAccounts = readableBuilder.build();
        writableAccounts = writableBuilder.build();

        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        readableAccountStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        given(context.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);

        given(writableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(writableAccounts);
        writableAccountStore = new WritableAccountStore(writableStates, writableEntityCounters);
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
    }

    public static AccountCustomizer newBuilder() {
        return new AccountCustomizer();
    }

    /**
     * A builder for {@link Account} instances.
     */
    private static final class AccountCustomizer {
        private Account accountOfInterest;
        private Long amount;
        private Long stakeAtStartOfLastRewardPeriod;
        private Boolean declineReward;
        private Boolean deleted;
        private Long stakePeriodStart;
        private AccountID stakedAccountId;
        private Long stakedNodeId;
        private Long stakedToMe;

        private AccountCustomizer() {}

        public Account build() {
            final var copy = accountOfInterest.copyBuilder();
            if (amount != null) {
                copy.tinybarBalance(amount);
            }
            if (stakeAtStartOfLastRewardPeriod != null) {
                copy.stakeAtStartOfLastRewardedPeriod(stakeAtStartOfLastRewardPeriod);
            }
            if (declineReward != null) {
                copy.declineReward(declineReward);
            }
            if (deleted != null) {
                copy.deleted(deleted);
            }
            if (stakePeriodStart != null) {
                copy.stakePeriodStart(stakePeriodStart);
            }
            if (stakedAccountId != null) {
                copy.stakedAccountId(stakedAccountId);
            } else if (stakedNodeId != null) {
                copy.stakedNodeId(stakedNodeId);
            }
            if (stakedToMe != null) {
                copy.stakedToMe(stakedToMe);
            }
            return copy.build();
        }

        public AccountCustomizer withAccount(final Account accountOfInterest) {
            this.accountOfInterest = accountOfInterest;
            return this;
        }

        public AccountCustomizer withBalance(final Long amount) {
            this.amount = amount;
            return this;
        }

        public AccountCustomizer withStakeAtStartOfLastRewardPeriod(final Long stakeAtStartOfLastRewardPeriod) {
            this.stakeAtStartOfLastRewardPeriod = stakeAtStartOfLastRewardPeriod;
            return this;
        }

        public AccountCustomizer withDeclineReward(final Boolean declineReward) {
            this.declineReward = declineReward;
            return this;
        }

        public AccountCustomizer withDeleted(final Boolean deleted) {
            this.deleted = deleted;
            return this;
        }

        public AccountCustomizer withStakePeriodStart(final Long stakePeriodStart) {
            this.stakePeriodStart = stakePeriodStart;
            return this;
        }

        public AccountCustomizer withStakedAccountId(final AccountID id) {
            this.stakedAccountId = id;
            return this;
        }

        public AccountCustomizer withStakedNodeId(final Long id) {
            this.stakedNodeId = id;
            return this;
        }

        public AccountCustomizer withStakedToMe(final long stakedToMe) {
            this.stakedToMe = stakedToMe;
            return this;
        }
    }
}
