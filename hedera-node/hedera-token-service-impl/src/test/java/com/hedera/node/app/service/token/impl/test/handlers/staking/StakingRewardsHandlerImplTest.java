// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.staking;

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
        given(manager.firstNonRewardableStakePeriod(readableRewardsStore)).willReturn(3L);
        given(account.stakePeriodStart()).willReturn(2L);

        final StakingRewardsHandlerImpl impl =
                new StakingRewardsHandlerImpl(rewardsPayer, manager, stakeInfoHelper, entityIdFactory);

        assertThat(impl.shouldUpdateStakeAtStartOfLastRewardPeriod(
                        account, true, 0L, readableRewardsStore, consensusInstant))
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
    void redirectsRewardForAccountDeletedInChildDispatch() {
        // Same reward situation as rewardsUltimateBeneficiaryInsteadOfDeletedAccount, except the
        // deleted -> beneficiary mapping is recorded on a CHILD dispatch builder (as happens for the
        // inner CryptoDelete of an atomic batch), not on the root builder consulted by the redirect.
        // The handler must fold the child mapping into the root builder, otherwise the redirect loop
        // throws IllegalStateException and the batch is rolled back to a zero-fee FAIL_INVALID record.
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
