// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_LABEL;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.NODE_REWARDS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.NODE_REWARDS_STATE_LABEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.platformstate.V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID;
import static org.hiero.consensus.platformstate.V0540PlatformStateSchema.PLATFORM_STATE_STATE_LABEL;
import static org.hiero.consensus.roster.RosterStateId.ROSTERS_STATE_ID;
import static org.hiero.consensus.roster.RosterStateId.ROSTERS_STATE_LABEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.hapi.node.state.token.NodeActivity;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.JudgeId;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.metrics.NodeMetrics;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.roster.RosterService;
import com.hedera.node.app.service.token.NodeRewardActivity;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.workflows.handle.record.SystemTransactions;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.hiero.consensus.roster.RosterStateId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
class NodeRewardManagerTest {

    private static final SemanticVersion CREATION_VERSION = new SemanticVersion(1, 2, 3, "alpha.1", "2");

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ConfigProvider configProvider;

    private EntityIdFactory entityIdFactory = new FakeEntityIdFactoryImpl(0, 0);

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private State state;

    private WritableStates writableStates;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ReadableStates readableStates;

    @Mock
    private SystemTransactions systemTransactions;

    private NodeRewardManager nodeRewardManager;
    private final AtomicReference<NodeRewards> nodeRewardsRef = new AtomicReference<>();
    private WritableSingletonStateBase<NodeRewards> nodeRewardsState;
    private final AtomicReference<PlatformState> stateRef = new AtomicReference<>();
    private final AtomicReference<NetworkStakingRewards> networkStakingRewardsRef = new AtomicReference<>();
    private final AtomicReference<RosterState> rosterStateRef = new AtomicReference<>();

    private static final Instant NOW = Instant.ofEpochSecond(1_234_567L);
    private static final Instant NOW_MINUS_600 = NOW.minusSeconds(600);
    private static final Instant PREV_PERIOD = NOW.minusSeconds(1500);

    @BeforeEach
    void setUp() {
        writableStates = mock(
                WritableStates.class,
                withSettings().extraInterfaces(CommittableWritableStates.class).strictness(Strictness.LENIENT));
        final var config = HederaTestConfigBuilder.create()
                .withValue("staking.periodMins", 1)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        nodeRewardManager = new NodeRewardManager(
                configProvider, entityIdFactory, exchangeRateManager, networkInfo, new NodeMetrics(new NoOpMetrics()));
    }

    @Test
    void testOnOpenBlockClearsAndLoadsState() {
        NodeRewards initialRewards = NodeRewards.newBuilder()
                .numRoundsInStakingPeriod(10)
                .nodeFeesCollected(1000)
                .nodeActivities(Collections.singletonList(NodeActivity.newBuilder()
                        .nodeId(101L)
                        .numMissedJudgeRounds(2)
                        .build()))
                .build();

        givenSetup(initialRewards, platformStateWithFreezeTime(null), null);

        nodeRewardManager.onOpenBlock(state);

        assertEquals(10, nodeRewardManager.getRoundsThisStakingPeriod());
        SortedMap<Long, Long> missedCounts = nodeRewardManager.getMissedJudgeCounts();
        assertEquals(1, missedCounts.size());
    }

    @Test
    void testUpdateJudgesOnEndRoundIncrementsRoundsAndMissedCounts() {
        assertEquals(0, nodeRewardManager.getRoundsThisStakingPeriod());
        givenSetup(NodeRewards.DEFAULT, platformStateWithFreezeTime(null), null);

        nodeRewardManager.updateJudgesOnEndRound(state);

        assertEquals(1, nodeRewardManager.getRoundsThisStakingPeriod());
        assertFalse(nodeRewardManager.getMissedJudgeCounts().isEmpty());

        nodeRewardManager.resetNodeRewards();
        assertEquals(0, nodeRewardManager.getRoundsThisStakingPeriod());
        assertTrue(nodeRewardManager.getMissedJudgeCounts().isEmpty());
    }

    @Test
    void testUpdateJudgesOnEndRoundDoesNotBackfillMissedJudgesForNewRosterEntries() {
        assertEquals(0, nodeRewardManager.getRoundsThisStakingPeriod());
        givenSetup(NodeRewards.DEFAULT, platformStateWithFreezeTime(null), null);

        for (int i = 0; i < 4; i++) {
            nodeRewardManager.updateJudgesOnEndRound(state);
        }

        // Add nodeId 2 to the active roster after 4 rounds
        final WritableKVState<ProtoBytes, Roster> rosters = MapWritableKVState.<ProtoBytes, Roster>builder(
                        ROSTERS_STATE_ID, ROSTERS_STATE_LABEL)
                .build();
        rosters.put(
                ProtoBytes.newBuilder().value(Bytes.wrap("ACTIVE")).build(),
                Roster.newBuilder()
                        .rosterEntries(List.of(
                                RosterEntry.newBuilder().nodeId(0L).build(),
                                RosterEntry.newBuilder().nodeId(1L).build(),
                                RosterEntry.newBuilder().nodeId(2L).build()))
                        .build());
        lenient().when(readableStates.<ProtoBytes, Roster>get(ROSTERS_STATE_ID)).thenReturn(rosters);

        nodeRewardManager.updateJudgesOnEndRound(state);

        // Now nodeId 2 should have only missed the current round (no backfill)
        assertEquals(5, nodeRewardManager.getRoundsThisStakingPeriod());
        assertEquals(5, nodeRewardManager.getMissedJudgeCounts().get(1L));
        assertEquals(1, nodeRewardManager.getMissedJudgeCounts().get(2L));

        nodeRewardManager.resetNodeRewards();
        assertEquals(0, nodeRewardManager.getRoundsThisStakingPeriod());
        assertTrue(nodeRewardManager.getMissedJudgeCounts().isEmpty());
    }

    @Test
    void testUpdateJudgesOnEndRoundSkipsNodesThatWereJudges() {
        assertEquals(0, nodeRewardManager.getRoundsThisStakingPeriod());
        givenSetup(NodeRewards.DEFAULT, platformStateWithJudges(List.of(0L, 1L)), null);

        nodeRewardManager.updateJudgesOnEndRound(state);

        assertEquals(1, nodeRewardManager.getRoundsThisStakingPeriod());
        assertTrue(nodeRewardManager.getMissedJudgeCounts().isEmpty());
    }

    @Test
    void testMaybeRewardActiveNodeRewardsDisabled() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.nodeRewardsEnabled", false)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1L));

        nodeRewardManager = new NodeRewardManager(
                configProvider, entityIdFactory, exchangeRateManager, networkInfo, new NodeMetrics(new NoOpMetrics()));

        nodeRewardManager.maybeRewardActiveNodes(state, Instant.now(), systemTransactions);
        verify(systemTransactions, never())
                .dispatchNodeRewards(any(), any(), any(), anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void testMaybeRewardActiveNodesWhenCurrentPeriod() {
        givenSetup(NodeRewards.DEFAULT, platformStateWithFreezeTime(null), null);
        nodeRewardManager = new NodeRewardManager(
                configProvider, entityIdFactory, exchangeRateManager, networkInfo, new NodeMetrics(new NoOpMetrics()));
        nodeRewardManager.maybeRewardActiveNodes(state, NOW_MINUS_600, systemTransactions);
        verify(systemTransactions, never())
                .dispatchNodeRewards(any(), any(), any(), anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void testMaybeRewardActiveNodesWhenPreviousPeriod() {
        final var networkStakingRewards = NetworkStakingRewards.newBuilder()
                .totalStakedStart(0)
                .totalStakedRewardStart(0)
                .pendingRewards(0)
                .lastNodeRewardPaymentsTime(asTimestamp(PREV_PERIOD))
                .stakingRewardsActivated(true)
                .build();
        givenSetup(NodeRewards.DEFAULT, platformStateWithFreezeTime(null), networkStakingRewards);
        nodeRewardManager = new NodeRewardManager(
                configProvider, entityIdFactory, exchangeRateManager, networkInfo, new NodeMetrics(new NoOpMetrics()));
        when(exchangeRateManager.getTinybarsFromTinycents(anyLong(), any())).thenReturn(5000L);

        nodeRewardManager.maybeRewardActiveNodes(state, NOW, systemTransactions);

        verify(systemTransactions).dispatchNodeRewards(any(), any(), any(), anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void testBuildNodeActivitiesExcludesUnknownNodes() {
        givenNotFoundNode(0L);
        givenNotFoundNode(1L);

        final var nodeRewards = NodeRewards.newBuilder()
                .numRoundsInStakingPeriod(100L)
                .nodeActivities(List.of())
                .build();
        final var activities =
                nodeRewardManager.buildNodeActivities(List.of(rosterEntry(0L), rosterEntry(1L)), nodeRewards, 80);

        assertTrue(activities.isEmpty());
    }

    @Test
    void testBuildNodeActivitiesIncludesAllKnownNodes() {
        // buildNodeActivities includes both declining and eligible nodes — declining filter is separate
        final var accountId0 = AccountID.newBuilder().accountNum(800L).build();
        final var accountId1 = AccountID.newBuilder().accountNum(801L).build();
        givenNodeWithAccount(0L, accountId0);
        givenNodeWithAccount(1L, accountId1);
        givenNotFoundNode(2L);

        final var nodeRewards = NodeRewards.newBuilder()
                .numRoundsInStakingPeriod(100L)
                .nodeActivities(List.of(NodeActivity.newBuilder()
                        .nodeId(0L)
                        .numMissedJudgeRounds(10L)
                        .build()))
                .build();
        final var activities = nodeRewardManager.buildNodeActivities(
                List.of(rosterEntry(0L), rosterEntry(1L), rosterEntry(2L)), nodeRewards, 80);

        assertThat(activities).hasSize(2);
        final var activity0 =
                activities.stream().filter(a -> a.nodeId() == 0L).findFirst().orElseThrow();
        assertEquals(accountId0, activity0.accountId());
        assertEquals(10L, activity0.numMissedRounds());
        assertEquals(100L, activity0.roundsInPeriod());

        // Node 1 has no entry in nodeActivities → defaults to 0 missed judges
        final var activity1 =
                activities.stream().filter(a -> a.nodeId() == 1L).findFirst().orElseThrow();
        assertEquals(accountId1, activity1.accountId());
        assertEquals(0L, activity1.numMissedRounds());
    }

    @Test
    void testExcludeNodesDecliningRewardsFiltersOut() {
        final var accountId0 = AccountID.newBuilder().accountNum(800L).build();
        final var accountId1 = AccountID.newBuilder().accountNum(801L).build();
        givenNodeDeclinesReward(0L, true);
        givenNodeDeclinesReward(1L, false);

        final var activities = List.of(
                new NodeRewardActivity(0L, accountId0, 5, 100, 80), new NodeRewardActivity(1L, accountId1, 5, 100, 80));
        final var eligible = nodeRewardManager.excludeNodesDecliningRewards(activities);

        assertThat(eligible).containsExactly(new NodeRewardActivity(1L, accountId1, 5, 100, 80));
    }

    @Test
    void testExcludeNodesDecliningRewardsMixedNodes() {
        final var accountId0 = AccountID.newBuilder().accountNum(802L).build();
        final var accountId1 = AccountID.newBuilder().accountNum(803L).build();
        final var accountId2 = AccountID.newBuilder().accountNum(804L).build();
        givenNodeDeclinesReward(0L, false);
        givenNodeDeclinesReward(1L, true);
        givenNotFoundNode(2L);

        final var activities = List.of(
                new NodeRewardActivity(0L, accountId0, 0, 100, 80),
                new NodeRewardActivity(1L, accountId1, 0, 100, 80),
                new NodeRewardActivity(2L, accountId2, 0, 100, 80));
        final var eligible = nodeRewardManager.excludeNodesDecliningRewards(activities);

        // Node 0 is eligible, node 1 declines, node 2 is unknown (null nodeInfo → excluded)
        assertThat(eligible).containsExactly(new NodeRewardActivity(0L, accountId0, 0, 100, 80));
    }

    @Test
    void testOnCloseBlockUpdatesMetricsEveryBlock() {
        final var initialRewards = NodeRewards.newBuilder()
                .numRoundsInStakingPeriod(10)
                .nodeFeesCollected(1000)
                .nodeActivities(List.of(
                        NodeActivity.newBuilder()
                                .nodeId(0L)
                                .numMissedJudgeRounds(2)
                                .build(),
                        NodeActivity.newBuilder()
                                .nodeId(1L)
                                .numMissedJudgeRounds(5)
                                .build()))
                .build();
        givenSetup(initialRewards, platformStateWithFreezeTime(null), null);
        givenNodeWithAccount(0L, AccountID.newBuilder().accountNum(800L).build());
        givenNodeWithAccount(1L, AccountID.newBuilder().accountNum(801L).build());
        final var metrics = mock(NodeMetrics.class);
        nodeRewardManager =
                new NodeRewardManager(configProvider, entityIdFactory, exchangeRateManager, networkInfo, metrics);
        nodeRewardManager.onOpenBlock(state);

        nodeRewardManager.onCloseBlock(state, 0L);

        verify(metrics).registerNodeMetrics(Set.of(0L, 1L));
        verify(metrics).updateNodeActiveMetrics(0L, 80.0);
        verify(metrics).updateNodeActiveMetrics(1L, 50.0);
    }

    private void givenSetup(
            NodeRewards nodeRewards,
            final PlatformState platformState,
            final NetworkStakingRewards networkStakingRewards) {
        nodeRewardsState = new FunctionWritableSingletonState<>(
                NODE_REWARDS_STATE_ID, NODE_REWARDS_STATE_LABEL, nodeRewardsRef::get, nodeRewardsRef::set);
        nodeRewardsRef.set(nodeRewards);
        rosterStateRef.set(RosterState.newBuilder()
                .roundRosterPairs(RoundRosterPair.newBuilder()
                        .roundNumber(0)
                        .activeRosterHash(Bytes.wrap("ACTIVE"))
                        .build())
                .build());
        stateRef.set(platformState);
        if (networkStakingRewards == null) {
            networkStakingRewardsRef.set(NetworkStakingRewards.newBuilder()
                    .totalStakedStart(0)
                    .totalStakedRewardStart(0)
                    .pendingRewards(0)
                    .lastNodeRewardPaymentsTime(asTimestamp(NOW_MINUS_600))
                    .stakingRewardsActivated(false)
                    .build());
        } else {
            networkStakingRewardsRef.set(networkStakingRewards);
        }

        lenient().when(state.getWritableStates(BlockStreamService.NAME)).thenReturn(writableStates);
        lenient().when(state.getReadableStates(BlockStreamService.NAME)).thenReturn(readableStates);
        lenient().when(state.getReadableStates(PlatformStateService.NAME)).thenReturn(readableStates);
        lenient().when(state.getReadableStates(TokenService.NAME)).thenReturn(readableStates);
        lenient().when(state.getWritableStates(TokenService.NAME)).thenReturn(writableStates);
        lenient().when(state.getReadableStates(RosterService.NAME)).thenReturn(readableStates);
        lenient().when(state.getWritableStates(RosterService.NAME)).thenReturn(writableStates);
        lenient().when(state.getReadableStates(EntityIdService.NAME)).thenReturn(readableStates);
        lenient().when(state.getWritableStates(EntityIdService.NAME)).thenReturn(writableStates);

        given(writableStates.<NodeRewards>getSingleton(NODE_REWARDS_STATE_ID)).willReturn(nodeRewardsState);
        given(readableStates.<NodeRewards>getSingleton(NODE_REWARDS_STATE_ID)).willReturn(nodeRewardsState);
        given(readableStates.<PlatformState>getSingleton(PLATFORM_STATE_STATE_ID))
                .willReturn(new FunctionWritableSingletonState<>(
                        PLATFORM_STATE_STATE_ID, PLATFORM_STATE_STATE_LABEL, stateRef::get, stateRef::set));
        given(readableStates.<RosterState>getSingleton(RosterStateId.ROSTER_STATE_STATE_ID))
                .willReturn(new FunctionWritableSingletonState<>(
                        RosterStateId.ROSTER_STATE_STATE_ID,
                        RosterStateId.ROSTER_STATE_STATE_LABEL,
                        rosterStateRef::get,
                        rosterStateRef::set));
        given(readableStates.getSingleton(ENTITY_ID_STATE_ID))
                .willReturn(new FunctionReadableSingletonState<>(
                        ENTITY_ID_STATE_ID, ENTITY_ID_STATE_LABEL, () -> EntityNumber.newBuilder()
                                .build()));
        given(readableStates.getSingleton(ENTITY_COUNTS_STATE_ID))
                .willReturn(new FunctionReadableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID,
                        ENTITY_COUNTS_STATE_LABEL,
                        () -> EntityCounts.newBuilder().numNodes(1).build()));
        final var networkRewardState = new FunctionWritableSingletonState<>(
                STAKING_NETWORK_REWARDS_STATE_ID,
                STAKING_NETWORK_REWARDS_STATE_LABEL,
                networkStakingRewardsRef::get,
                networkStakingRewardsRef::set);
        final var readableNetworkRewardState = new FunctionReadableSingletonState<>(
                STAKING_NETWORK_REWARDS_STATE_ID, STAKING_NETWORK_REWARDS_STATE_LABEL, networkStakingRewardsRef::get);
        given(readableStates.<NetworkStakingRewards>getSingleton(STAKING_NETWORK_REWARDS_STATE_ID))
                .willReturn(networkRewardState);
        given(writableStates.<NetworkStakingRewards>getSingleton(STAKING_NETWORK_REWARDS_STATE_ID))
                .willReturn(networkRewardState);
        final WritableKVState<ProtoBytes, Roster> rosters = MapWritableKVState.<ProtoBytes, Roster>builder(
                        ROSTERS_STATE_ID, ROSTERS_STATE_LABEL)
                .build();
        rosters.put(
                ProtoBytes.newBuilder().value(Bytes.wrap("ACTIVE")).build(),
                Roster.newBuilder()
                        .rosterEntries(List.of(
                                RosterEntry.newBuilder().nodeId(0L).build(),
                                RosterEntry.newBuilder().nodeId(1L).build()))
                        .build());
        lenient().when(readableStates.<ProtoBytes, Roster>get(ROSTERS_STATE_ID)).thenReturn(rosters);
        final var readableAccounts = MapWritableKVState.<AccountID, Account>builder(
                        ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL)
                .value(asAccount(0, 0, 801), Account.DEFAULT)
                .build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        given(writableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
    }

    private PlatformState platformStateWithFreezeTime(@Nullable final Instant freezeTime) {
        return PlatformState.newBuilder()
                .creationSoftwareVersion(CREATION_VERSION)
                .consensusSnapshot(ConsensusSnapshot.newBuilder()
                        .judgeIds(List.of(new JudgeId(0, Bytes.wrap("test"))))
                        .build())
                .freezeTime(freezeTime == null ? null : asTimestamp(freezeTime))
                .build();
    }

    private PlatformState platformStateWithJudges(final List<Long> judgeNodeIds) {
        final var judgeIds = judgeNodeIds.stream()
                .map(nodeId -> new JudgeId(nodeId, Bytes.wrap(("test-" + nodeId).getBytes())))
                .toList();
        return PlatformState.newBuilder()
                .creationSoftwareVersion(CREATION_VERSION)
                .consensusSnapshot(
                        ConsensusSnapshot.newBuilder().judgeIds(judgeIds).build())
                .build();
    }

    /**
     * Tests that when the network is down for multiple days, only ONE node reward
     * distribution is triggered when the network comes back up, not one for each missed day.
     * <p>
     * This is the current expected behavior - the system only checks if we're in a "later" period,
     * not how many periods were skipped.
     */
    @Test
    void testMaybeRewardActiveNodesAfterMultiDayOutageOnlyRewardsOnce() {
        // Simulate a 3-day outage: last reward payment was 3 days ago
        final var threeDaysAgo = NOW.minusSeconds(3 * 24 * 60 * 60);
        final var networkStakingRewards = NetworkStakingRewards.newBuilder()
                .totalStakedStart(0)
                .totalStakedRewardStart(0)
                .pendingRewards(0)
                .lastNodeRewardPaymentsTime(asTimestamp(threeDaysAgo))
                .stakingRewardsActivated(true)
                .build();
        givenSetup(NodeRewards.DEFAULT, platformStateWithFreezeTime(null), networkStakingRewards);
        nodeRewardManager = new NodeRewardManager(
                configProvider, entityIdFactory, exchangeRateManager, networkInfo, new NodeMetrics(new NoOpMetrics()));
        when(exchangeRateManager.getTinybarsFromTinycents(anyLong(), any())).thenReturn(5000L);

        // First call - should reward
        final var result1 = nodeRewardManager.maybeRewardActiveNodes(state, NOW, systemTransactions);
        assertTrue(result1, "First reward after multi-day outage should succeed");

        // Verify rewards were dispatched exactly once
        verify(systemTransactions, times(1))
                .dispatchNodeRewards(any(), any(), any(), anyLong(), any(), anyLong(), anyLong());

        // The lastNodeRewardPaymentsTime should now be updated to NOW
        // So a second call in the same period should NOT reward again
        final var result2 = nodeRewardManager.maybeRewardActiveNodes(state, NOW, systemTransactions);
        assertFalse(result2, "Second reward in same period should not happen");

        // Still only one dispatch
        verify(systemTransactions, times(1))
                .dispatchNodeRewards(any(), any(), any(), anyLong(), any(), anyLong(), anyLong());
    }

    /** Stubs networkInfo to return a node with the given account ID (used by buildNodeActivities). */
    private void givenNodeWithAccount(long nodeId, @NonNull AccountID accountId) {
        final var nodeInfo = mock(NodeInfo.class);
        given(nodeInfo.accountId()).willReturn(accountId);
        given(networkInfo.nodeInfo(nodeId)).willReturn(nodeInfo);
    }

    /** Stubs networkInfo to return a node with the given declineReward flag (used by excludeNodesDecliningRewards). */
    private void givenNodeDeclinesReward(long nodeId, boolean declines) {
        final var nodeInfo = mock(NodeInfo.class);
        given(nodeInfo.declineReward()).willReturn(declines);
        given(networkInfo.nodeInfo(nodeId)).willReturn(nodeInfo);
    }

    private void givenNotFoundNode(long nodeId) {
        given(networkInfo.nodeInfo(nodeId)).willReturn(null);
    }

    private static RosterEntry rosterEntry(long nodeId) {
        return RosterEntry.newBuilder().nodeId(nodeId).build();
    }
}
