// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_KEY;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.NODE_REWARDS_KEY;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_KEY;
import static com.swirlds.platform.state.service.schemas.V0540RosterBaseSchema.ROSTER_KEY;
import static com.swirlds.platform.state.service.schemas.V0540RosterBaseSchema.ROSTER_STATES_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

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
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.metrics.NodeMetrics;
import com.hedera.node.app.roster.RosterService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.workflows.handle.record.SystemTransactions;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.EntityIdFactory;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.roster.WritableRosterStore;
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
        // to make it work for the multiple node in one JVM case, we need reset the default instance path every time
        // we create another instance of MerkleDB.
        MerkleDb.resetDefaultInstancePath();
        writableStates = mock(
                WritableStates.class,
                withSettings().extraInterfaces(CommittableWritableStates.class).strictness(Strictness.LENIENT));
        final var config = HederaTestConfigBuilder.create()
                .withValue("staking.periodMins", 1)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        nodeRewardManager = new NodeRewardManager(
                configProvider, entityIdFactory, exchangeRateManager, new NodeMetrics(new NoOpMetrics()));
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
    void testUpdateJudgesOnEndRoundSetsNewNodeInActiveRosterMissedJudgesToCurrentTotalRoundsInStakingPeriod() {
        assertEquals(0, nodeRewardManager.getRoundsThisStakingPeriod());
        givenSetup(NodeRewards.DEFAULT, platformStateWithFreezeTime(null), null);

        for (int i = 0; i < 9; i++) {
            nodeRewardManager.updateJudgesOnEndRound(state);
        }

        // Add nodeId 2 to the active roster after 9 rounds
        final WritableKVState<ProtoBytes, Roster> rosters = MapWritableKVState.<ProtoBytes, Roster>builder(
                        RosterService.NAME, WritableRosterStore.ROSTER_KEY)
                .build();
        rosters.put(
                ProtoBytes.newBuilder().value(Bytes.wrap("ACTIVE")).build(),
                Roster.newBuilder()
                        .rosterEntries(List.of(
                                RosterEntry.newBuilder().nodeId(0L).build(),
                                RosterEntry.newBuilder().nodeId(1L).build(),
                                RosterEntry.newBuilder().nodeId(2L).build()))
                        .build());
        lenient().when(readableStates.<ProtoBytes, Roster>get(ROSTER_KEY)).thenReturn(rosters);

        nodeRewardManager.updateJudgesOnEndRound(state);

        // Now nodeId 2 should have missed judges equal to the current rounds in staking period
        assertEquals(10, nodeRewardManager.getRoundsThisStakingPeriod());
        assertEquals(10, nodeRewardManager.getMissedJudgeCounts().get(1L));
        assertEquals(10, nodeRewardManager.getMissedJudgeCounts().get(2L));

        nodeRewardManager.resetNodeRewards();
        assertEquals(0, nodeRewardManager.getRoundsThisStakingPeriod());
        assertTrue(nodeRewardManager.getMissedJudgeCounts().isEmpty());
    }

    @Test
    void testMaybeRewardActiveNodeRewardsDisabled() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.nodeRewardsEnabled", false)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1L));

        nodeRewardManager = new NodeRewardManager(
                configProvider, entityIdFactory, exchangeRateManager, new NodeMetrics(new NoOpMetrics()));

        nodeRewardManager.maybeRewardActiveNodes(state, Instant.now(), systemTransactions);
        verify(systemTransactions, never())
                .dispatchNodeRewards(any(), any(), any(), anyLong(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void testMaybeRewardActiveNodesWhenCurrentPeriod() {
        givenSetup(NodeRewards.DEFAULT, platformStateWithFreezeTime(null), null);
        nodeRewardManager = new NodeRewardManager(
                configProvider, entityIdFactory, exchangeRateManager, new NodeMetrics(new NoOpMetrics()));
        nodeRewardManager.maybeRewardActiveNodes(state, NOW_MINUS_600, systemTransactions);
        verify(systemTransactions, never())
                .dispatchNodeRewards(any(), any(), any(), anyLong(), any(), anyLong(), anyLong(), any());
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
                configProvider, entityIdFactory, exchangeRateManager, new NodeMetrics(new NoOpMetrics()));
        when(exchangeRateManager.getTinybarsFromTinycents(anyLong(), any())).thenReturn(5000L);

        nodeRewardManager.maybeRewardActiveNodes(state, NOW, systemTransactions);

        verify(systemTransactions)
                .dispatchNodeRewards(any(), any(), any(), anyLong(), any(), anyLong(), anyLong(), any());
    }

    private void givenSetup(
            NodeRewards nodeRewards,
            final PlatformState platformState,
            final NetworkStakingRewards networkStakingRewards) {
        nodeRewardsState = new FunctionWritableSingletonState<>(
                TokenService.NAME, NODE_REWARDS_KEY, nodeRewardsRef::get, nodeRewardsRef::set);
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

        given(writableStates.<NodeRewards>getSingleton(NODE_REWARDS_KEY)).willReturn(nodeRewardsState);
        given(readableStates.<NodeRewards>getSingleton(NODE_REWARDS_KEY)).willReturn(nodeRewardsState);
        given(readableStates.<PlatformState>getSingleton(PLATFORM_STATE_KEY))
                .willReturn(new FunctionWritableSingletonState<>(
                        PlatformStateService.NAME, PLATFORM_STATE_KEY, stateRef::get, stateRef::set));
        given(readableStates.<RosterState>getSingleton(ROSTER_STATES_KEY))
                .willReturn(new FunctionWritableSingletonState<>(
                        RosterService.NAME, ROSTER_STATES_KEY, rosterStateRef::get, rosterStateRef::set));
        given(readableStates.getSingleton(ENTITY_ID_STATE_KEY))
                .willReturn(new FunctionReadableSingletonState<>(
                        EntityIdService.NAME, ENTITY_ID_STATE_KEY, () -> EntityNumber.newBuilder()
                                .build()));
        given(readableStates.getSingleton(ENTITY_COUNTS_KEY))
                .willReturn(new FunctionReadableSingletonState<>(
                        EntityIdService.NAME,
                        ENTITY_COUNTS_KEY,
                        () -> EntityCounts.newBuilder().numNodes(1).build()));
        final var networkRewardState = new FunctionWritableSingletonState<>(
                TokenService.NAME,
                STAKING_NETWORK_REWARDS_KEY,
                networkStakingRewardsRef::get,
                networkStakingRewardsRef::set);
        final var readableNetworkRewardState = new FunctionReadableSingletonState<>(
                TokenService.NAME, STAKING_NETWORK_REWARDS_KEY, networkStakingRewardsRef::get);
        given(readableStates.<NetworkStakingRewards>getSingleton(STAKING_NETWORK_REWARDS_KEY))
                .willReturn(networkRewardState);
        given(writableStates.<NetworkStakingRewards>getSingleton(STAKING_NETWORK_REWARDS_KEY))
                .willReturn(networkRewardState);
        //        given(readableNetworkRewardState.get()).willReturn(networkStakingRewardsRef.get());
        final WritableKVState<ProtoBytes, Roster> rosters = MapWritableKVState.<ProtoBytes, Roster>builder(
                        RosterService.NAME, WritableRosterStore.ROSTER_KEY)
                .build();
        rosters.put(
                ProtoBytes.newBuilder().value(Bytes.wrap("ACTIVE")).build(),
                Roster.newBuilder()
                        .rosterEntries(List.of(
                                RosterEntry.newBuilder().nodeId(0L).build(),
                                RosterEntry.newBuilder().nodeId(1L).build()))
                        .build());
        lenient().when(readableStates.<ProtoBytes, Roster>get(ROSTER_KEY)).thenReturn(rosters);
        final var readableAccounts = MapWritableKVState.<AccountID, Account>builder(TokenService.NAME, ACCOUNTS_KEY)
                .value(asAccount(0, 0, 801), Account.DEFAULT)
                .build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_KEY)).willReturn(readableAccounts);
        given(writableStates.<AccountID, Account>get(ACCOUNTS_KEY)).willReturn(readableAccounts);
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
}
