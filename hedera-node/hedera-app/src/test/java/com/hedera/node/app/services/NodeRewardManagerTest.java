// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_ID;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_LABEL;
import static com.hedera.node.app.service.addressbook.impl.schemas.V073AddressBookSchema.REGISTERED_NODES_KEY;
import static com.hedera.node.app.service.addressbook.impl.schemas.V073AddressBookSchema.REGISTERED_NODES_STATE_ID;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.hedera.hapi.node.addressbook.RegisteredServiceEndpoint;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.addressbook.RegisteredNode;
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
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.roster.RosterService;
import com.hedera.node.app.service.token.NodeRewardActivity;
import com.hedera.node.app.service.token.NodeRewardAmounts;
import com.hedera.node.app.service.token.NodeRewardGroups;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.workflows.handle.record.SystemTransactions;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.NodesConfig;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
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
    public static final long MIN_REWARD = 10L;
    public static final long TARGET_REWARD = 100L;
    private static final long BLOCK_NODE_REWARD = 50L;
    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(800L).build();
    private static final long NODE_0_ID = 0L;
    private static final long NODE_1_ID = 1L;
    private static final long NODE_2_ID = 2L;
    private static final long NODE_3_ID = 3L;
    private static final long NODE_4_ID = 4L;
    private static final long NODE_5_ID = 5L;
    private static final AccountID NODE_0_ACCOUNT =
            AccountID.newBuilder().accountNum(1000L).build();
    private static final AccountID NODE_1_ACCOUNT =
            AccountID.newBuilder().accountNum(1001L).build();
    private static final AccountID NODE_2_ACCOUNT =
            AccountID.newBuilder().accountNum(1002L).build();
    private static final AccountID NODE_3_ACCOUNT =
            AccountID.newBuilder().accountNum(1003L).build();
    private static final AccountID NODE_4_ACCOUNT =
            AccountID.newBuilder().accountNum(1004L).build();
    private static final AccountID NODE_5_ACCOUNT =
            AccountID.newBuilder().accountNum(1005L).build();
    private static final Map<Long, AccountID> NODE_ACCOUNTS = Map.of(
            NODE_0_ID, NODE_0_ACCOUNT,
            NODE_1_ID, NODE_1_ACCOUNT,
            NODE_2_ID, NODE_2_ACCOUNT,
            NODE_3_ID, NODE_3_ACCOUNT,
            NODE_4_ID, NODE_4_ACCOUNT,
            NODE_5_ID, NODE_5_ACCOUNT);
    private static final long REGISTERED_BLOCK_NODE_ID = 42L;
    private static final long REGISTERED_MIRROR_NODE_ID = 43L;

    // Accumulators for address book state — fresh per test (JUnit per-method lifecycle)
    private final SortedMap<Long, Node> addressBookNodes = new TreeMap<>();
    private final SortedMap<Long, RegisteredNode> registeredNodesMap = new TreeMap<>();

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ConfigProvider configProvider;

    private final EntityIdFactory entityIdFactory = new FakeEntityIdFactoryImpl(0, 0);

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
        givenAddressBookState();
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
        verify(systemTransactions, never()).dispatchNodeRewards(any(), any(), any());
    }

    @Test
    void testMaybeRewardActiveNodesWhenCurrentPeriod() {
        givenSetup(NodeRewards.DEFAULT, platformStateWithFreezeTime(null), null);
        nodeRewardManager = new NodeRewardManager(
                configProvider, entityIdFactory, exchangeRateManager, networkInfo, new NodeMetrics(new NoOpMetrics()));
        nodeRewardManager.maybeRewardActiveNodes(state, NOW_MINUS_600, systemTransactions);
        verify(systemTransactions, never()).dispatchNodeRewards(any(), any(), any());
    }

    @Test
    void testMaybeRewardActiveNodesWhenPreviousPeriod() {
        final var rewardPerNode = 5000L;
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
        givenExchangeRates(rewardPerNode);

        nodeRewardManager.maybeRewardActiveNodes(state, NOW, systemTransactions);

        verify(systemTransactions).dispatchNodeRewards(any(), any(), any());
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
        final var missed = 0L;
        final var rounds = 100L;
        final var thresholdPercent = 80;
        givenNodeDeclinesReward(0L, false);
        givenNodeDeclinesReward(1L, true);
        givenNotFoundNode(2L);

        final var activities = List.of(
                new NodeRewardActivity(NODE_0_ID, NODE_0_ACCOUNT, missed, rounds, thresholdPercent),
                new NodeRewardActivity(NODE_1_ID, NODE_1_ACCOUNT, missed, rounds, thresholdPercent),
                new NodeRewardActivity(NODE_2_ID, NODE_2_ACCOUNT, missed, rounds, thresholdPercent));
        final var eligible = nodeRewardManager.excludeNodesDecliningRewards(activities);

        // Node 0 is eligible, node 1 declines, node 2 is unknown (null nodeInfo → excluded)
        assertThat(eligible)
                .containsExactly(new NodeRewardActivity(0L, NODE_0_ACCOUNT, missed, rounds, thresholdPercent));
    }

    @Test
    void testMaybeRewardActiveNodesEmitsRewardMetrics() {
        final var rewardPerNode = 5000L;
        final var networkStakingRewards = NetworkStakingRewards.newBuilder()
                .totalStakedStart(0)
                .totalStakedRewardStart(0)
                .pendingRewards(0)
                .lastNodeRewardPaymentsTime(asTimestamp(PREV_PERIOD))
                .stakingRewardsActivated(true)
                .build();
        givenSetup(NodeRewards.DEFAULT, platformStateWithFreezeTime(null), networkStakingRewards);
        final var metrics = mock(NodeMetrics.class);
        nodeRewardManager =
                new NodeRewardManager(configProvider, entityIdFactory, exchangeRateManager, networkInfo, metrics);
        givenExchangeRates(rewardPerNode);

        nodeRewardManager.maybeRewardActiveNodes(state, NOW, systemTransactions);

        verify(metrics).updateRewardMetrics(any(NodeRewardAmounts.class), anyInt());
    }

    @Test
    void testOnCloseBlockUpdatesMetricsEveryBlock() {
        final var initialRewards = NodeRewards.newBuilder()
                .numRoundsInStakingPeriod(10)
                .nodeFeesCollected(1000)
                .nodeActivities(List.of(
                        NodeActivity.newBuilder()
                                .nodeId(NODE_0_ID)
                                .numMissedJudgeRounds(2)
                                .build(),
                        NodeActivity.newBuilder()
                                .nodeId(NODE_1_ID)
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
        final WritableSingletonStateBase<NodeRewards> nodeRewardsState = new FunctionWritableSingletonState<>(
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
        final var networkRewardState = new FunctionWritableSingletonState<>(
                STAKING_NETWORK_REWARDS_STATE_ID,
                STAKING_NETWORK_REWARDS_STATE_LABEL,
                networkStakingRewardsRef::get,
                networkStakingRewardsRef::set);
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
                                RosterEntry.newBuilder().nodeId(NODE_0_ID).build(),
                                RosterEntry.newBuilder().nodeId(NODE_1_ID).build()))
                        .build());
        lenient().when(readableStates.<ProtoBytes, Roster>get(ROSTERS_STATE_ID)).thenReturn(rosters);
        final var readableAccounts = MapWritableKVState.<AccountID, Account>builder(
                        ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL)
                .value(asAccount(0, 0, 801), Account.DEFAULT)
                .build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        given(writableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
    }

    /**
     * Sets up the minimal state stubs required by {@link NodeRewardManager#findBlockNodeEligibleNodeIds}.
     * Stubs {@code AddressBookService} and {@code EntityIdService} readable states, with empty
     * node and registered-node KV stores by default. Call {@link #givenAssociatedBlockNodes} and
     * {@link #givenRegisteredBlockNode} after this to populate them.
     */
    private void givenAddressBookState() {
        lenient().when(state.getReadableStates(AddressBookService.NAME)).thenReturn(readableStates);
        lenient().when(state.getReadableStates(EntityIdService.NAME)).thenReturn(readableStates);
        lenient()
                .when(readableStates.getSingleton(ENTITY_ID_STATE_ID))
                .thenReturn(new FunctionReadableSingletonState<>(
                        ENTITY_ID_STATE_ID, ENTITY_ID_STATE_LABEL, () -> EntityNumber.newBuilder()
                                .build()));
        lenient()
                .when(readableStates.getSingleton(ENTITY_COUNTS_STATE_ID))
                .thenReturn(new FunctionReadableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID,
                        ENTITY_COUNTS_STATE_LABEL,
                        () -> EntityCounts.newBuilder().numNodes(1).build()));
        stubAddressBookNodesState();
        stubRegisteredNodesState();
    }

    private void stubAddressBookNodesState() {
        final var builder = MapWritableKVState.<EntityNumber, Node>builder(NODES_STATE_ID, NODES_STATE_LABEL);
        addressBookNodes.forEach(
                (id, node) -> builder.value(EntityNumber.newBuilder().number(id).build(), node));
        lenient().when(readableStates.<EntityNumber, Node>get(NODES_STATE_ID)).thenReturn(builder.build());
    }

    private void stubRegisteredNodesState() {
        final var builder = MapWritableKVState.<EntityNumber, RegisteredNode>builder(
                REGISTERED_NODES_STATE_ID, REGISTERED_NODES_KEY);
        registeredNodesMap.forEach(
                (id, node) -> builder.value(EntityNumber.newBuilder().number(id).build(), node));
        lenient()
                .when(readableStates.<EntityNumber, RegisteredNode>get(REGISTERED_NODES_STATE_ID))
                .thenReturn(builder.build());
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
        final var rewardPerNode = 5000L;
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
        givenExchangeRates(rewardPerNode);

        // First call - should reward
        final var result1 = nodeRewardManager.maybeRewardActiveNodes(state, NOW, systemTransactions);
        assertTrue(result1, "First reward after multi-day outage should succeed");

        // Verify rewards were dispatched exactly once
        verify(systemTransactions, times(1)).dispatchNodeRewards(any(), any(), any());

        // The lastNodeRewardPaymentsTime should now be updated to NOW
        // So a second call in the same period should NOT reward again
        final var result2 = nodeRewardManager.maybeRewardActiveNodes(state, NOW, systemTransactions);
        assertFalse(result2, "Second reward in same period should not happen");

        // Still only one dispatch
        verify(systemTransactions, times(1)).dispatchNodeRewards(any(), any(), any());
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

    // =============== Tests for calculateRewardAmounts ===============

    @Test
    void testCalculateRewardAmountsBudgetSufficientForAll() {
        final var activeCount = 2;
        final var inactiveCount = 1;
        final var budgetBalance = 300L;

        // Given: 2 active nodes at 100 each, 1 inactive at 10, balance = 300 (sufficient for 210)
        final var nodeGroups = createTestNodeGroups(activeCount, inactiveCount);
        givenExchangeRates(MIN_REWARD, TARGET_REWARD);
        final var nodesConfig = configProvider.getConfiguration().getConfigData(NodesConfig.class);

        final var result = nodeRewardManager.calculateRewardAmounts(
                nodeGroups, givenBudget(budgetBalance, 0L), nodesConfig, NOW, state);

        assertThat(result.activeTotalAmount()).isEqualTo(activeCount * TARGET_REWARD);
        assertThat(result.inactiveTotalAmount()).isEqualTo(inactiveCount * MIN_REWARD);
        assertThat(result.totalAmount()).isEqualTo((activeCount * TARGET_REWARD) + (inactiveCount * MIN_REWARD));
        assertRewardAmountForNodes(List.of(NODE_1_ID, NODE_2_ID), result, TARGET_REWARD);
        assertRewardAmountForNodes(List.of(NODE_3_ID), result, MIN_REWARD);
    }

    @Test
    void testCalculateRewardAmountsBudgetExactlyActiveTotal() {
        final var activeCount = 2;
        final var budgetBalance = activeCount * TARGET_REWARD;

        // Given: 2 active nodes at 100 each, 1 inactive at 10, balance = 200 (== activeTotal)
        // balance == activeTotal → Case 3 (balance not strictly greater than activeTotal),
        // so active nodes get 200/2 = 100 each, inactive dropped
        final var nodeGroups = createTestNodeGroups(activeCount, 1);
        givenExchangeRates(MIN_REWARD, TARGET_REWARD);
        final var nodesConfig = configProvider.getConfiguration().getConfigData(NodesConfig.class);

        final var result = nodeRewardManager.calculateRewardAmounts(
                nodeGroups, givenBudget(budgetBalance, 0L), nodesConfig, NOW, state);

        assertThat(result.activeTotalAmount()).isEqualTo(activeCount * TARGET_REWARD);
        assertThat(result.inactiveTotalAmount()).isEqualTo(0L); // dropped
        assertThat(result.totalAmount()).isEqualTo(activeCount * TARGET_REWARD);
        assertRewardAmountForNodes(List.of(NODE_1_ID, NODE_2_ID), result, TARGET_REWARD);
    }

    @Test
    void testCalculateRewardAmountsBudgetActiveTotalPlusOne() {
        final var activeCount = 2;
        final var budgetBalance = (activeCount * TARGET_REWARD) + 1L;

        // Given: 2 active at 100 each (total=200), 1 inactive at 10, balance = 201 (== activeTotal + 1)
        // balance > activeTotal → Case 2: remainder = 1, inactive gets 1/1 = 1
        final var nodeGroups = createTestNodeGroups(activeCount, 1);
        givenExchangeRates(MIN_REWARD, TARGET_REWARD);
        final var nodesConfig = configProvider.getConfiguration().getConfigData(NodesConfig.class);

        final var result = nodeRewardManager.calculateRewardAmounts(
                nodeGroups, givenBudget(budgetBalance, 0L), nodesConfig, NOW, state);

        assertThat(result.activeTotalAmount()).isEqualTo(activeCount * TARGET_REWARD);
        assertThat(result.inactiveTotalAmount()).isEqualTo(1L); // min(1, 10) / 1 = 1
        assertThat(result.totalAmount()).isEqualTo((activeCount * TARGET_REWARD) + 1L);
        assertRewardAmountForNodes(List.of(NODE_1_ID, NODE_2_ID), result, TARGET_REWARD);
        assertRewardAmountForNodes(List.of(NODE_3_ID), result, 1L);
    }

    @Test
    void testCalculateRewardAmountsBudgetTooSmallToPayAnyone() {
        final var activeCount = 3;
        final var budgetBalance = 2L;

        // Given: 3 active nodes at 100 each, balance = 2 → Case 3: 2/3 = 0 per node → empty result
        final var nodeGroups = createTestNodeGroups(activeCount, 0);
        givenExchangeRates(MIN_REWARD, TARGET_REWARD);
        final var nodesConfig = configProvider.getConfiguration().getConfigData(NodesConfig.class);

        final var result = nodeRewardManager.calculateRewardAmounts(
                nodeGroups, givenBudget(budgetBalance, 0L), nodesConfig, NOW, state);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.totalAmount()).isEqualTo(0L);
    }

    @Test
    void testCalculateRewardAmountsBudgetPartiallyFundsInactive() {
        final var activeCount = 2;
        final var inactiveCount = 2;
        final var remainderForInactive = 10L;
        final var budgetBalance = (activeCount * TARGET_REWARD) + remainderForInactive;

        // Given: 2 active at 100 each (total=200), 2 inactive at 10 each (total=20), balance=210
        // balance > activeTotal (210 > 200) → Case 2: remainder = 10, each inactive gets 10/2=5
        final var nodeGroups = createTestNodeGroups(activeCount, inactiveCount);
        givenExchangeRates(MIN_REWARD, TARGET_REWARD);
        final var nodesConfig = configProvider.getConfiguration().getConfigData(NodesConfig.class);

        final var result = nodeRewardManager.calculateRewardAmounts(
                nodeGroups, givenBudget(budgetBalance, 0L), nodesConfig, NOW, state);

        final var perInactiveReward = remainderForInactive / inactiveCount;
        assertThat(result.activeTotalAmount()).isEqualTo(activeCount * TARGET_REWARD);
        assertThat(result.inactiveTotalAmount()).isEqualTo(remainderForInactive);
        assertThat(result.totalAmount()).isEqualTo((activeCount * TARGET_REWARD) + remainderForInactive);
        assertRewardAmountForNodes(List.of(NODE_1_ID, NODE_2_ID), result, TARGET_REWARD);
        assertRewardAmountForNodes(List.of(NODE_3_ID, NODE_4_ID), result, perInactiveReward);
    }

    @Test
    void testCalculateRewardAmountsBudgetInsufficientForActive() {
        final var activeCount = 3;
        final var budgetBalance = 150L;

        // Given: 3 active nodes at 100 each, balance = 150 → 150/3=50 each
        final var nodeGroups = createTestNodeGroups(activeCount, 2);
        givenExchangeRates(MIN_REWARD, TARGET_REWARD);
        final var nodesConfig = configProvider.getConfiguration().getConfigData(NodesConfig.class);

        final var result = nodeRewardManager.calculateRewardAmounts(
                nodeGroups, givenBudget(budgetBalance, 0L), nodesConfig, NOW, state);

        final var perActiveReward = budgetBalance / activeCount;
        assertThat(result.activeTotalAmount()).isEqualTo(activeCount * perActiveReward);
        assertThat(result.inactiveTotalAmount()).isEqualTo(0L);
        assertThat(result.totalAmount()).isEqualTo(activeCount * perActiveReward);
        assertRewardAmountForNodes(List.of(NODE_1_ID, NODE_2_ID, NODE_3_ID), result, perActiveReward);
    }

    @Test
    void testCalculateRewardAmountsNoActiveNodes() {
        final var inactiveCount = 2;
        final var budgetBalance = 100L;

        // Given: Only inactive nodes at 10 each, balance = 100 (sufficient)
        final var nodeGroups = createTestNodeGroups(0, inactiveCount);
        givenExchangeRates(MIN_REWARD, TARGET_REWARD);
        final var nodesConfig = configProvider.getConfiguration().getConfigData(NodesConfig.class);

        final var result = nodeRewardManager.calculateRewardAmounts(
                nodeGroups, givenBudget(budgetBalance, 0L), nodesConfig, NOW, state);

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.activeTotalAmount()).isEqualTo(0L);
        assertThat(result.inactiveTotalAmount()).isEqualTo(inactiveCount * MIN_REWARD);
        assertThat(result.totalAmount()).isEqualTo(inactiveCount * MIN_REWARD);
        assertRewardAmountForNodes(List.of(NODE_1_ID, NODE_2_ID), result, MIN_REWARD); // inactive nodes 1 and 2
    }

    @Test
    void testCalculateRewardAmountsNoActiveNodesInsufficientBalance() {
        final var inactiveCount = 3;
        final var budgetBalance = 20L;

        // Given: 3 inactive at 10 each (total=30), balance=20 → Case 4: 20/3=6 each
        final var nodeGroups = createTestNodeGroups(0, inactiveCount);
        givenExchangeRates(MIN_REWARD, TARGET_REWARD);
        final var nodesConfig = configProvider.getConfiguration().getConfigData(NodesConfig.class);

        final var result = nodeRewardManager.calculateRewardAmounts(
                nodeGroups, givenBudget(budgetBalance, 0L), nodesConfig, NOW, state);

        final var perInactiveReward = budgetBalance / inactiveCount;
        assertThat(result.isEmpty()).isFalse();
        assertThat(result.activeTotalAmount()).isEqualTo(0L);
        assertThat(result.inactiveTotalAmount()).isEqualTo(inactiveCount * perInactiveReward);
        assertRewardAmountForNodes(List.of(NODE_1_ID, NODE_2_ID, NODE_3_ID), result, perInactiveReward);
    }

    @Test
    void testCalculateRewardAmountsZeroBalance() {
        final var budgetBalance = 0L;

        // Given: Balance is zero — no rewards paid
        final var nodeGroups = createTestNodeGroups(2, 1);
        givenExchangeRates(MIN_REWARD, TARGET_REWARD);
        final var nodesConfig = configProvider.getConfiguration().getConfigData(NodesConfig.class);

        final var result = nodeRewardManager.calculateRewardAmounts(
                nodeGroups, givenBudget(budgetBalance, 0L), nodesConfig, NOW, state);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.totalAmount()).isEqualTo(0L);
    }

    @Test
    void testCalculateRewardAmountsMinRewardZero() {
        final var activeCount = 2;
        final var inactiveCount = 1;
        final var budgetBalance = 500L;

        // Given: Min reward = 0 → inactive nodes receive nothing
        final var nodeGroups = createTestNodeGroups(activeCount, inactiveCount);
        givenExchangeRates(0L, TARGET_REWARD);
        final var nodesConfig = configProvider.getConfiguration().getConfigData(NodesConfig.class);

        final var result = nodeRewardManager.calculateRewardAmounts(
                nodeGroups, givenBudget(budgetBalance, 0L), nodesConfig, NOW, state);

        assertThat(result.activeTotalAmount()).isEqualTo(activeCount * TARGET_REWARD);
        assertThat(result.inactiveTotalAmount()).isEqualTo(0L); // min=0, skipped
        assertThat(result.totalAmount()).isEqualTo(activeCount * TARGET_REWARD);
        assertRewardAmountForNodes(List.of(NODE_1_ID, NODE_2_ID), result, TARGET_REWARD);
    }

    @Test
    void testCalculateRewardAmountsPrePaidRewardsExceedTarget() {
        final var prePaidRewards = 150L;
        final var activeCount = 2;
        final var budgetBalance = 500L;

        // Given: prePaidRewards (150) > target (100) → perNodeReward floors at minNodeReward (10)
        final var nodeGroups = createTestNodeGroups(activeCount, 0);
        givenExchangeRates(MIN_REWARD, TARGET_REWARD);
        final var nodesConfig = configProvider.getConfiguration().getConfigData(NodesConfig.class);

        final var result = nodeRewardManager.calculateRewardAmounts(
                nodeGroups, givenBudget(budgetBalance, prePaidRewards), nodesConfig, NOW, state);

        assertThat(result.activeTotalAmount()).isEqualTo(activeCount * MIN_REWARD);
        assertThat(result.totalAmount()).isEqualTo(activeCount * MIN_REWARD);
        assertRewardAmountForNodes(List.of(NODE_1_ID, NODE_2_ID), result, MIN_REWARD);
    }

    @Test
    void testCalculateRewardAmountsWithBlockNodeRewards() {
        final var budgetBalance = 1000L;

        // Node 1 is a block node operator → gets consensus + block; node 2 gets consensus only
        final var nodesConfig = givenOneUsdPerDayBlockNodeConfig();
        givenAssociatedBlockNodes(NODE_1_ID, REGISTERED_BLOCK_NODE_ID);
        givenRegisteredBlockNode(REGISTERED_BLOCK_NODE_ID);

        final var nodeGroups = createTestNodeGroups(2, 0);
        givenExchangeRates(MIN_REWARD, TARGET_REWARD, BLOCK_NODE_REWARD);

        final var result = nodeRewardManager.calculateRewardAmounts(
                nodeGroups, givenBudget(budgetBalance, 0L), nodesConfig, NOW, state);

        assertThat(result.activeTotalAmount()).isEqualTo(2 * TARGET_REWARD + BLOCK_NODE_REWARD);
        assertRewardAmountForNodes(List.of(NODE_1_ID), result, TARGET_REWARD + BLOCK_NODE_REWARD);
        assertRewardAmountForNodes(List.of(NODE_2_ID), result, TARGET_REWARD);
    }

    @Test
    void testCalculateRewardAmountsMixedActiveNodesWithAndWithoutBlockNode() {
        final var budgetBalance = 1000L;
        final var activeCount = 3;
        final var inactiveCount = 1;

        // 2 active block-node operators (node 1 via registered 42, node 2 via registered 44),
        // 1 active consensus-only node, 1 inactive node — sufficient budget for all
        final long secondBlockNodeId = 44L;
        final var nodesConfig = givenOneUsdPerDayBlockNodeConfig();
        givenAssociatedBlockNodes(NODE_1_ID, REGISTERED_BLOCK_NODE_ID);
        givenAssociatedBlockNodes(NODE_2_ID, secondBlockNodeId);
        givenRegisteredBlockNode(REGISTERED_BLOCK_NODE_ID);
        givenRegisteredBlockNode(secondBlockNodeId);

        // createTestNodeGroups(3, 1): active = node 1, 2, 3; inactive = node 4
        final var nodeGroups = createTestNodeGroups(activeCount, inactiveCount);
        // min=10, consensus target=100, block node reward=50
        givenExchangeRates(MIN_REWARD, TARGET_REWARD, BLOCK_NODE_REWARD);

        final var result = nodeRewardManager.calculateRewardAmounts(
                nodeGroups, givenBudget(budgetBalance, 0L), nodesConfig, NOW, state);

        final var expectedNode1Reward = TARGET_REWARD + BLOCK_NODE_REWARD;
        final var expectedNode2Reward = TARGET_REWARD + BLOCK_NODE_REWARD;
        final var expectedNode3Reward = TARGET_REWARD;
        final var expectedActiveTotal = expectedNode1Reward + expectedNode2Reward + expectedNode3Reward;
        final var expectedInactiveTotal = inactiveCount * MIN_REWARD;

        assertThat(result.activeTotalAmount()).isEqualTo(expectedActiveTotal);
        assertThat(result.inactiveTotalAmount()).isEqualTo(expectedInactiveTotal);
        assertThat(result.totalAmount()).isEqualTo(expectedActiveTotal + expectedInactiveTotal);
        assertRewardAmountForNodes(List.of(NODE_1_ID), result, expectedNode1Reward);
        assertRewardAmountForNodes(List.of(NODE_2_ID), result, expectedNode2Reward);
        assertRewardAmountForNodes(List.of(NODE_3_ID), result, expectedNode3Reward);
        assertRewardAmountForNodes(List.of(NODE_4_ID), result, MIN_REWARD);
    }

    @Test
    void testCalculateRewardAmountsBlockNodeBudgetCapsInactive() {
        final var activeCount = 2;
        final var inactiveCount = 1;

        final var activeTotal = TARGET_REWARD + BLOCK_NODE_REWARD + TARGET_REWARD; // 250
        final var budgetBalance = activeTotal + 5L; // 255

        // Active total = 250 (node 1: 150, node 2: 100), inactive = 10
        // Budget = 255 → Case 2: active paid in full, remainder (5) caps inactive
        final var nodesConfig = givenOneUsdPerDayBlockNodeConfig();
        givenAssociatedBlockNodes(NODE_1_ID, REGISTERED_BLOCK_NODE_ID);
        givenRegisteredBlockNode(REGISTERED_BLOCK_NODE_ID);

        final var nodeGroups = createTestNodeGroups(activeCount, inactiveCount);
        givenExchangeRates(MIN_REWARD, TARGET_REWARD, BLOCK_NODE_REWARD);

        final var result = nodeRewardManager.calculateRewardAmounts(
                nodeGroups, givenBudget(budgetBalance, 0L), nodesConfig, NOW, state);

        // Active paid in full; inactive budget = 255 - 250 = 5 (< 10)
        assertThat(result.activeTotalAmount()).isEqualTo(activeTotal);
        assertThat(result.inactiveTotalAmount()).isEqualTo(budgetBalance - activeTotal);
        assertRewardAmountForNodes(List.of(NODE_1_ID), result, TARGET_REWARD + BLOCK_NODE_REWARD);
        assertRewardAmountForNodes(List.of(NODE_2_ID), result, TARGET_REWARD);
        assertRewardAmountForNodes(List.of(NODE_3_ID), result, budgetBalance - activeTotal);
    }

    @Test
    void testCalculateRewardAmountsBlockNodeBudgetInsufficientForActive() {
        final var activeCount = 2;
        final var budgetBalance = 100L;

        // Active total = 250 (node 1: 150, node 2: 100), budget = 100 < active total
        // Case 3: divide 100 equally among 2 active nodes → 50 each (block reward identity absorbed)
        final var nodesConfig = givenOneUsdPerDayBlockNodeConfig();
        givenAssociatedBlockNodes(NODE_1_ID, REGISTERED_BLOCK_NODE_ID);
        givenRegisteredBlockNode(REGISTERED_BLOCK_NODE_ID);

        final var nodeGroups = createTestNodeGroups(activeCount, 0);
        // min=10, consensus target=100, block node reward=50
        givenExchangeRates(MIN_REWARD, TARGET_REWARD, BLOCK_NODE_REWARD);

        final var result = nodeRewardManager.calculateRewardAmounts(
                nodeGroups, givenBudget(budgetBalance, 0L), nodesConfig, NOW, state);

        final var perActiveReward = budgetBalance / activeCount;
        // Both active nodes receive equal share; block node reward identity is absorbed into equal distribution
        assertThat(result.activeTotalAmount()).isEqualTo(budgetBalance);
        assertThat(result.inactiveTotalAmount()).isEqualTo(0L);
        assertRewardAmountForNodes(List.of(NODE_1_ID), result, perActiveReward);
        assertRewardAmountForNodes(List.of(NODE_2_ID), result, perActiveReward);
    }

    // =============== Tests for applyBudgetConstraints ===============

    @Test
    void testApplyBudgetConstraintsDeduplicatesMultipleRewardTypesForSameNode() {
        final var budgetBalance = 100L;

        // Given: node 1 has both consensus (100) and block (100) rewards → activeTotalAmount = 200
        final var desiredAmounts = new NodeRewardAmounts(PAYER_ID);
        desiredAmounts.addConsensusNodeReward(NODE_1_ID, NODE_1_ACCOUNT, TARGET_REWARD);
        desiredAmounts.addConsensusNodeReward(NODE_2_ID, NODE_2_ACCOUNT, TARGET_REWARD);
        desiredAmounts.addBlockNodeReward(NODE_1_ID, NODE_1_ACCOUNT, TARGET_REWARD);

        // Balance = 100 < activeTotalAmount (200) → triggers equal distribution among active nodes
        // activeNodeCount = 1 (seenNodes deduplicates), so perNodeAmount = 100 / 2 = 50
        final var result = nodeRewardManager.applyBudgetConstraints(desiredAmounts, budgetBalance, PAYER_ID);

        // Node 1 should receive exactly 100, not 200 — the second reward entry is skipped by seenNodes
        assertThat(result.activeTotalAmount()).isEqualTo(budgetBalance);
        assertThat(result.activeNodeCount()).isEqualTo(2);
    }

    // =============== Tests for computeActiveBlockNodeRewards ===============

    @Test
    void testComputeActiveBlockNodeRewardsNoEligibleNodes() {
        final var nodesConfig = configProvider.getConfiguration().getConfigData(NodesConfig.class);

        NodeRewardAmounts rewardAmounts = newRewardAmounts();
        nodeRewardManager.computeActiveBlockNodeRewards(
                activitiesForNodes(NODE_1_ID), givenFoundBlockNodeIds(), nodesConfig, NOW, rewardAmounts);

        assertThat(rewardAmounts.isEmpty()).isTrue();
    }

    @Test
    void testComputeActiveBlockNodeRewardsZeroTargetYearly() {
        // targetYearlyBlockNodeRewardsUsd = 0 (default) → no rewards even for eligible nodes
        final var rewardAmounts = newRewardAmounts();
        final var nodesConfig = configProvider.getConfiguration().getConfigData(NodesConfig.class);

        nodeRewardManager.computeActiveBlockNodeRewards(
                activitiesForNodes(NODE_1_ID), givenFoundBlockNodeIds(NODE_1_ID), nodesConfig, NOW, rewardAmounts);

        assertThat(rewardAmounts.isEmpty()).isTrue();
    }

    @Test
    void testComputeActiveBlockNodeRewardsFeatureFlagDisabledSkipsRewards() {
        // blockNodeRewardsEnabled = false (default) → no rewards even when target USD is set and nodes are eligible
        final var config = HederaTestConfigBuilder.create()
                .withValue("staking.periodMins", 1)
                .withValue("nodes.blockNodeRewardsEnabled", false)
                .withValue("nodes.targetYearlyBlockNodeRewardsUsd", 365)
                .withValue("nodes.numPeriodsToTargetUsd", 365)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        nodeRewardManager = new NodeRewardManager(
                configProvider, entityIdFactory, exchangeRateManager, networkInfo, new NodeMetrics(new NoOpMetrics()));
        final var nodesConfig = config.getConfigData(NodesConfig.class);

        final var rewardAmounts = newRewardAmounts();
        nodeRewardManager.computeActiveBlockNodeRewards(
                activitiesForNodes(NODE_1_ID), givenFoundBlockNodeIds(NODE_1_ID), nodesConfig, NOW, rewardAmounts);

        assertThat(rewardAmounts.isEmpty()).isTrue();
    }

    @Test
    void testComputeActiveBlockNodeRewardsEligibleNodeGetsReward() {
        final var nodesConfig = givenOneUsdPerDayBlockNodeConfig();
        final var rewardAmounts = newRewardAmounts();
        givenExchangeRates(BLOCK_NODE_REWARD);

        // Only node 1 is eligible; node 2 is not
        nodeRewardManager.computeActiveBlockNodeRewards(
                activitiesForNodes(NODE_1_ID, NODE_2_ID),
                givenFoundBlockNodeIds(NODE_1_ID),
                nodesConfig,
                NOW,
                rewardAmounts);

        assertThat(rewardAmounts.activeTotalAmount()).isEqualTo(BLOCK_NODE_REWARD);
        assertRewardAmountForNodes(List.of(NODE_1_ID), rewardAmounts, BLOCK_NODE_REWARD);
        assertRewardAmountForNodes(List.of(NODE_2_ID), rewardAmounts, 0L);
    }

    @Test
    void testComputeActiveBlockNodeRewardsMultipleEligibleNodes() {
        final var nodesConfig = givenOneUsdPerDayBlockNodeConfig();
        final var rewardAmounts = newRewardAmounts();
        final var activeCount = 2;
        givenExchangeRates(BLOCK_NODE_REWARD);

        nodeRewardManager.computeActiveBlockNodeRewards(
                activitiesForNodes(NODE_1_ID, NODE_2_ID),
                givenFoundBlockNodeIds(NODE_1_ID, NODE_2_ID),
                nodesConfig,
                NOW,
                rewardAmounts);

        final var expectedActiveTotal = activeCount * BLOCK_NODE_REWARD;
        assertThat(rewardAmounts.activeTotalAmount()).isEqualTo(expectedActiveTotal);
    }

    // =============== Tests for findBlockNodeEligibleNodeIds ===============

    @Test
    void testFindBlockNodeEligibleNodeIdsNoNodesInStore() {
        final var result = nodeRewardManager.findBlockNodeEligibleNodeIds(state, activitiesForNodes(NODE_0_ID));

        assertThat(result).isEmpty();
    }

    @Test
    void testFindBlockNodeEligibleNodeIdsNodeWithBlockNodeEndpoint() {
        givenAssociatedBlockNodes(NODE_0_ID, REGISTERED_BLOCK_NODE_ID);
        givenRegisteredBlockNode(REGISTERED_BLOCK_NODE_ID);

        final var result = nodeRewardManager.findBlockNodeEligibleNodeIds(state, activitiesForNodes(NODE_0_ID));

        assertThat(result).containsExactly(NODE_0_ID);
    }

    @Test
    void testFindBlockNodeEligibleNodeIdsNodeWithMirrorNodeEndpointOnly() {
        givenAssociatedBlockNodes(NODE_0_ID, REGISTERED_MIRROR_NODE_ID);
        givenRegisteredMirrorNode(REGISTERED_MIRROR_NODE_ID);

        final var result = nodeRewardManager.findBlockNodeEligibleNodeIds(state, activitiesForNodes(NODE_0_ID));

        assertThat(result).isEmpty();
    }

    @Test
    void testFindBlockNodeEligibleNodeIdsNodeWithNoAssociatedRegisteredNodes() {
        givenAssociatedBlockNodes(NODE_0_ID); // no associated block nodes

        final var result = nodeRewardManager.findBlockNodeEligibleNodeIds(state, activitiesForNodes(NODE_0_ID));

        assertThat(result).isEmpty();
    }

    @Test
    void testFindBlockNodeEligibleNodeIdsDeduplicatesSharedBlockNode() {
        // Both consensus nodes share the same registered block node — only the first is eligible
        givenAssociatedBlockNodes(NODE_0_ID, REGISTERED_BLOCK_NODE_ID);
        givenAssociatedBlockNodes(NODE_1_ID, REGISTERED_BLOCK_NODE_ID);
        givenRegisteredBlockNode(REGISTERED_BLOCK_NODE_ID);

        final var result =
                nodeRewardManager.findBlockNodeEligibleNodeIds(state, activitiesForNodes(NODE_0_ID, NODE_1_ID));

        assertThat(result).containsExactly(NODE_0_ID);
    }

    @Test
    void testFindBlockNodeEligibleNodeIdsFallsBackToUnclaimedBlockNode() {
        // Node 1 claims block node 42; node 2 also has 42 (already claimed) but also has unclaimed 44
        final long otherBlockNodeId = 44L;
        givenAssociatedBlockNodes(NODE_0_ID, REGISTERED_BLOCK_NODE_ID);
        givenAssociatedBlockNodes(NODE_1_ID, REGISTERED_BLOCK_NODE_ID, otherBlockNodeId);
        givenRegisteredBlockNode(REGISTERED_BLOCK_NODE_ID);
        givenRegisteredBlockNode(otherBlockNodeId);

        final var result =
                nodeRewardManager.findBlockNodeEligibleNodeIds(state, activitiesForNodes(NODE_0_ID, NODE_1_ID));

        // Both are eligible: node 0 via block node 42, node 1 via block node 44
        assertThat(result).containsExactlyInAnyOrder(NODE_0_ID, NODE_1_ID);
    }

    /**
     * Asserts that each node in {@code nodeIds} received exactly {@code expectedAmount} tinybars
     * in the given {@code amounts}.
     */
    private void assertRewardAmountForNodes(
            final List<Long> nodeIds, final NodeRewardAmounts amounts, final long expectedAmount) {
        final var transferList = amounts.toTransferList();
        for (final long nodeId : nodeIds) {
            final var nodeAccount = NODE_ACCOUNTS.get(nodeId);
            final long actual = transferList.accountAmounts().stream()
                    .filter(aa -> aa.accountID().equals(nodeAccount))
                    .mapToLong(AccountAmount::amount)
                    .findFirst()
                    .orElse(0L);
            assertThat(actual)
                    .as("node %d should receive %d tinybars", nodeId, expectedAmount)
                    .isEqualTo(expectedAmount);
        }
    }

    /**
     * Stubs the exchange rate manager to return the given values in sequence.
     */
    private void givenExchangeRates(final long firstReward, final long... otherRewards) {
        when(exchangeRateManager.getTinybarsFromTinycents(anyLong(), eq(NOW)))
                .thenReturn(firstReward, Arrays.stream(otherRewards).boxed().toArray(Long[]::new));
    }

    /**
     * Returns a {@link Set} of block-node-eligible node IDs.
     */
    private static Set<Long> givenFoundBlockNodeIds(final Long... nodeIds) {
        return Set.of(nodeIds);
    }

    private NodeRewardGroups createTestNodeGroups(final int activeCount, final int inactiveCount) {
        final var activeActivities = new ArrayList<NodeRewardActivity>();
        final var inactiveActivities = new ArrayList<NodeRewardActivity>();

        for (int i = 0; i < activeCount; i++) {
            final var nodeId = (long) (i + 1);
            activeActivities.add(new NodeRewardActivity(nodeId, NODE_ACCOUNTS.get(nodeId), 0, 100, 33));
        }

        for (int i = 0; i < inactiveCount; i++) {
            final var nodeId = (long) (activeCount + i + 1);
            inactiveActivities.add(new NodeRewardActivity(nodeId, NODE_ACCOUNTS.get(nodeId), 70, 100, 33));
        }

        return NodeRewardGroups.from(new ArrayList<NodeRewardActivity>() {
            {
                addAll(activeActivities);
                addAll(inactiveActivities);
            }
        });
    }

    /** Creates a list of {@link NodeRewardActivity} for the given node IDs. */
    private static List<NodeRewardActivity> activitiesForNodes(final Long... nodeIds) {
        return Arrays.stream(nodeIds)
                .map(id -> new NodeRewardActivity(id, NODE_ACCOUNTS.get(id), 0, 100, 33))
                .toList();
    }

    /** Creates an empty {@link NodeRewardAmounts} backed by {@link #PAYER_ID}. */
    private static NodeRewardAmounts newRewardAmounts() {
        return new NodeRewardAmounts(PAYER_ID);
    }

    private static NodeRewardManager.RewardBudget givenBudget(long balance, long prePaidRewards) {
        return new NodeRewardManager.RewardBudget(balance, prePaidRewards);
    }

    /**
     * Configures block node rewards with a target of $1/day (365 USD/year over 365 periods)
     * and rebuilds the {@link NodeRewardManager}.
     * Returns the resulting {@link NodesConfig} for use in the test.
     */
    private NodesConfig givenOneUsdPerDayBlockNodeConfig() {
        return givenBlockNodeRewardConfig(365, 365);
    }

    /**
     * Configures block node rewards and rebuilds the {@link NodeRewardManager}.
     * Returns the resulting {@link NodesConfig} for use in the test.
     */
    private NodesConfig givenBlockNodeRewardConfig(long numPeriods, long yearlyRewardUsd) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("staking.periodMins", 1)
                .withValue("nodes.blockNodeRewardsEnabled", true)
                .withValue("nodes.targetYearlyBlockNodeRewardsUsd", yearlyRewardUsd)
                .withValue("nodes.numPeriodsToTargetUsd", numPeriods)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        nodeRewardManager = new NodeRewardManager(
                configProvider, entityIdFactory, exchangeRateManager, networkInfo, new NodeMetrics(new NoOpMetrics()));
        return config.getConfigData(NodesConfig.class);
    }

    /**
     * Adds the given node to the address book state with the specified associated registered node IDs.
     * Subsequent calls accumulate — all registered nodes remain visible in the stub.
     */
    private void givenAssociatedBlockNodes(long nodeId, Long... associatedRegisteredNodeIds) {
        addressBookNodes.put(
                nodeId,
                Node.newBuilder()
                        .nodeId(nodeId)
                        .associatedRegisteredNode(List.of(associatedRegisteredNodeIds))
                        .build());
        stubAddressBookNodesState();
    }

    /**
     * Stubs the {@code REGISTERED_NODES} KV state so that the given ID maps to a
     * {@link RegisteredNode} with a Block Node service endpoint.
     */
    private void givenRegisteredBlockNode(long registeredNodeId) {
        givenRegisteredNode(
                registeredNodeId,
                RegisteredServiceEndpoint.newBuilder()
                        .blockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                                .build())
                        .build());
    }

    /**
     * Stubs the {@code REGISTERED_NODES} KV state so that the given ID maps to a
     * {@link RegisteredNode} with a Mirror Node service endpoint.
     */
    private void givenRegisteredMirrorNode(long registeredNodeId) {
        givenRegisteredNode(
                registeredNodeId,
                RegisteredServiceEndpoint.newBuilder()
                        .mirrorNode(RegisteredServiceEndpoint.MirrorNodeEndpoint.DEFAULT)
                        .build());
    }

    private void givenRegisteredNode(long registeredNodeId, RegisteredServiceEndpoint endpoint) {
        registeredNodesMap.put(
                registeredNodeId,
                RegisteredNode.newBuilder()
                        .registeredNodeId(registeredNodeId)
                        .serviceEndpoint(List.of(endpoint))
                        .build());
        stubRegisteredNodesState();
    }
}
