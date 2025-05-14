// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.staking;

import static com.hedera.hapi.node.base.HederaFunctionality.NODE_STAKE_UPDATE;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_KEY;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static com.hedera.node.app.service.token.Units.HBARS_TO_TINYBARS;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFO_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.WritableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUpdater;
import com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUtils;
import com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper;
import com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory;
import com.hedera.node.app.service.token.records.NodeStakeUpdateStreamBuilder;
import com.hedera.node.app.service.token.records.TokenContext;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.StakingConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.state.lifecycle.EntityIdFactory;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link EndOfStakingPeriodUpdater}.
 */
@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
public class EndOfStakingPeriodUpdaterTest {

    @LoggingTarget
    private LogCaptor logCaptor;

    @Mock
    private TokenContext context;

    @Mock
    private NodeStakeUpdateStreamBuilder nodeStakeUpdateRecordBuilder;

    @Mock
    private EntityIdFactory entityIdFactory;

    private ReadableAccountStore accountStore;

    @LoggingSubject
    private EndOfStakingPeriodUpdater subject;

    private WritableStakingInfoStore stakingInfoStore;
    private WritableNetworkStakingRewardsStore stakingRewardsStore;

    private static final ConfigProvider DEFAULT_CONFIG_PROVIDER = HederaTestConfigBuilder.createConfigProvider();

    @BeforeEach
    void setup() {
        accountStore = TestStoreFactory.newReadableStoreWithAccounts(Account.newBuilder()
                .accountId(asAccount(0L, 0L, 800))
                .tinybarBalance(100_000_000_000L)
                .build());
        subject = new EndOfStakingPeriodUpdater(
                new StakingRewardsHelper(DEFAULT_CONFIG_PROVIDER), DEFAULT_CONFIG_PROVIDER, entityIdFactory);
    }

    @Test
    void calculatesMidnightTimeCorrectly() {
        final var consensusSecs = 1653660350L;
        final var consensusNanos = 12345L;
        final var expectedNanos = 999_999_999;
        final var consensusTime = Instant.ofEpochSecond(consensusSecs, consensusNanos);
        final var expectedMidnightTime =
                Timestamp.newBuilder().seconds(1653609599L).nanos(expectedNanos).build();

        assertThat(EndOfStakingPeriodUtils.lastInstantOfPreviousPeriodFor(consensusTime))
                .isEqualTo(expectedMidnightTime);
    }

    private void commonSetup(
            final long totalStakeRewardStart,
            @NonNull final StakingNodeInfo info1,
            @NonNull final StakingNodeInfo info2,
            @NonNull final StakingNodeInfo info3) {
        given(context.consensusTime()).willReturn(Instant.now());

        // Create staking config
        final var stakingConfig = newStakingConfig().getOrCreateConfig();
        given(context.configuration()).willReturn(stakingConfig);

        // Create account store (with data)
        given(context.readableStore(ReadableAccountStore.class)).willReturn(accountStore);

        // Create staking info store (with data)
        MapWritableKVState<EntityNumber, StakingNodeInfo> stakingInfosState = new MapWritableKVState.Builder<
                        EntityNumber, StakingNodeInfo>(TokenService.NAME, STAKING_INFO_KEY)
                .value(NODE_NUM_1, info1)
                .value(NODE_NUM_2, info2)
                .value(NODE_NUM_3, info3)
                .build();
        final var entityIdStore = new WritableEntityIdStore(new MapWritableStates(Map.of(
                ENTITY_ID_STATE_KEY,
                new FunctionWritableSingletonState<>(EntityIdService.NAME, ENTITY_ID_STATE_KEY, () -> null, c -> {}),
                ENTITY_COUNTS_KEY,
                new FunctionWritableSingletonState<>(EntityIdService.NAME, ENTITY_COUNTS_KEY, () -> null, c -> {}))));
        stakingInfoStore = new WritableStakingInfoStore(
                new MapWritableStates(Map.of(STAKING_INFO_KEY, stakingInfosState)), entityIdStore);
        given(context.writableStore(WritableStakingInfoStore.class)).willReturn(stakingInfoStore);

        // Create staking reward store (with data)
        final var backingValue =
                new AtomicReference<>(new NetworkStakingRewards(true, totalStakeRewardStart, 0, 0, Timestamp.DEFAULT));
        WritableSingletonState<NetworkStakingRewards> stakingRewardsState = new FunctionWritableSingletonState<>(
                TokenService.NAME, STAKING_NETWORK_REWARDS_KEY, backingValue::get, backingValue::set);
        final var states = mock(WritableStates.class);
        given(states.getSingleton(STAKING_NETWORK_REWARDS_KEY))
                .willReturn((WritableSingletonState) stakingRewardsState);
        stakingRewardsStore = new WritableNetworkStakingRewardsStore(states);
        given(context.writableStore(WritableNetworkStakingRewardsStore.class)).willReturn(stakingRewardsStore);
        given(context.addPrecedingChildRecordBuilder(NodeStakeUpdateStreamBuilder.class, NODE_STAKE_UPDATE))
                .willReturn(nodeStakeUpdateRecordBuilder);
        given(context.knownNodeIds()).willReturn(Set.of(NODE_NUM_1.number(), NODE_NUM_2.number(), NODE_NUM_3.number()));
    }

    private static final int SUM_OF_CONSENSUS_WEIGHTS = 500;
    private static final long MIN_STAKE = 100L * HBARS_TO_TINYBARS;
    private static final long MAX_STAKE = 800L * HBARS_TO_TINYBARS;
    private static final long STAKE_TO_REWARD_1 = 700L * HBARS_TO_TINYBARS;
    private static final long STAKE_TO_REWARD_2 = 300L * HBARS_TO_TINYBARS;
    private static final long STAKE_TO_REWARD_3 = 30L * HBARS_TO_TINYBARS;
    private static final long STAKE_TO_NOT_REWARD_1 = 300L * HBARS_TO_TINYBARS;
    private static final long STAKE_TO_NOT_REWARD_2 = 200L * HBARS_TO_TINYBARS;
    private static final long STAKE_TO_NOT_REWARD_3 = 20L * HBARS_TO_TINYBARS;
    private static final long STAKED_REWARD_START_1 = 1_000L * HBARS_TO_TINYBARS;
    private static final long UNCLAIMED_STAKED_REWARD_START_1 = STAKED_REWARD_START_1 / 10;
    private static final long STAKED_REWARD_START_2 = 700L * HBARS_TO_TINYBARS;
    private static final long UNCLAIMED_STAKED_REWARD_START_2 = STAKED_REWARD_START_2 / 10;
    private static final long STAKED_REWARD_START_3 = 10_000L * HBARS_TO_TINYBARS;
    private static final long UNCLAIMED_STAKED_REWARD_START_3 = STAKED_REWARD_START_3 / 10;
    private static final long STAKE_1 = 2_000L * HBARS_TO_TINYBARS;
    private static final long STAKE_2 = 750L * HBARS_TO_TINYBARS;
    private static final long STAKE_3 = 75L * HBARS_TO_TINYBARS;
    private static final List<Long> REWARD_SUM_HISTORY_1 = List.of(8L, 7L, 2L);
    private static final List<Long> REWARD_SUM_HISTORY_2 = List.of(5L, 5L, 4L);
    private static final List<Long> REWARD_SUM_HISTORY_3 = List.of(4L, 2L, 1L);
    /**
     * Node number 1 for the test nodes.
     */
    public static final EntityNumber NODE_NUM_1 =
            EntityNumber.newBuilder().number(1).build();
    /**
     * Node number 2 for the test nodes.
     */
    public static final EntityNumber NODE_NUM_2 =
            EntityNumber.newBuilder().number(2).build();
    /**
     * Node number 3 for the test nodes.
     */
    public static final EntityNumber NODE_NUM_3 =
            EntityNumber.newBuilder().number(3).build();
    /**
     * Node number 4 for the test nodes.
     */
    public static final EntityNumber NODE_NUM_4 =
            EntityNumber.newBuilder().number(4).build();
    /**
     * Node number 8 for the test nodes.
     */
    public static final EntityNumber NODE_NUM_8 =
            EntityNumber.newBuilder().number(8).build();
    /**
     * Staking info for node 1.
     */
    public static final StakingNodeInfo STAKING_INFO_1 = StakingNodeInfo.newBuilder()
            .nodeNumber(NODE_NUM_1.number())
            .minStake(MIN_STAKE)
            .maxStake(MAX_STAKE)
            .stakeToReward(STAKE_TO_REWARD_1)
            .stakeToNotReward(STAKE_TO_NOT_REWARD_1)
            .stakeRewardStart(STAKED_REWARD_START_1)
            .unclaimedStakeRewardStart(UNCLAIMED_STAKED_REWARD_START_1)
            .stake(STAKE_1)
            .rewardSumHistory(REWARD_SUM_HISTORY_1)
            .deleted(false)
            .weight(0)
            .build();
    /**
     * Staking info for node 2.
     */
    public static final StakingNodeInfo STAKING_INFO_2 = StakingNodeInfo.newBuilder()
            .nodeNumber(NODE_NUM_2.number())
            .minStake(MIN_STAKE)
            .maxStake(MAX_STAKE)
            .stakeToReward(STAKE_TO_REWARD_2)
            .stakeToNotReward(STAKE_TO_NOT_REWARD_2)
            .stakeRewardStart(STAKED_REWARD_START_2)
            .unclaimedStakeRewardStart(UNCLAIMED_STAKED_REWARD_START_2)
            .stake(STAKE_2)
            .rewardSumHistory(REWARD_SUM_HISTORY_2)
            .deleted(false)
            .weight(0)
            .build();
    /**
     * Staking info for node 3.
     */
    public static final StakingNodeInfo STAKING_INFO_3 = StakingNodeInfo.newBuilder()
            .nodeNumber(NODE_NUM_3.number())
            .minStake(MIN_STAKE)
            .maxStake(MAX_STAKE)
            .stakeToReward(STAKE_TO_REWARD_3)
            .stakeToNotReward(STAKE_TO_NOT_REWARD_3)
            .stakeRewardStart(STAKED_REWARD_START_3)
            .unclaimedStakeRewardStart(UNCLAIMED_STAKED_REWARD_START_3)
            .stake(STAKE_3)
            .rewardSumHistory(REWARD_SUM_HISTORY_3)
            .deleted(false)
            .weight(0)
            .build();

    private static TestConfigBuilder newStakingConfig() {
        return HederaTestConfigBuilder.create()
                .withConfigDataType(StakingConfig.class)
                .withValue("staking.rewardRate", 100L)
                .withValue("staking.sumOfConsensusWeights", SUM_OF_CONSENSUS_WEIGHTS)
                .withValue("staking.maxStakeRewarded", Long.MAX_VALUE)
                .withValue("staking.perHbarRewardRate", 100L)
                .withValue("staking.rewardBalanceThreshold", 0);
    }
}
