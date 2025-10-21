// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.staking;

import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_LABEL;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFOS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFOS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.test.WritableStakingInfoStoreImplTest.NODE_ID_1;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.EndOfStakingPeriodUpdaterTest.NODE_NUM_1;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.EndOfStakingPeriodUpdaterTest.NODE_NUM_2;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.EndOfStakingPeriodUpdaterTest.NODE_NUM_3;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.EndOfStakingPeriodUpdaterTest.NODE_NUM_4;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.EndOfStakingPeriodUpdaterTest.NODE_NUM_8;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.EndOfStakingPeriodUpdaterTest.STAKING_INFO_1;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.EndOfStakingPeriodUpdaterTest.STAKING_INFO_2;
import static com.hedera.node.app.service.token.impl.test.handlers.staking.EndOfStakingPeriodUpdaterTest.STAKING_INFO_3;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.entityid.impl.WritableEntityIdStoreImpl;
import com.hedera.node.app.service.token.impl.WritableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.app.service.token.impl.handlers.staking.StakeInfoHelper;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.spi.fixtures.info.FakeNetworkInfo;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StakeInfoHelperTest {

    public static final Configuration DEFAULT_CONFIG = HederaTestConfigBuilder.createConfig();

    private WritableStakingInfoStore infoStore;

    @Mock
    private WritableNetworkStakingRewardsStore rewardsStore;

    private final StakeInfoHelper subject = new StakeInfoHelper();

    private WritableEntityIdStoreImpl entityIdStore;

    @BeforeEach
    void setup() {
        entityIdStore = new WritableEntityIdStoreImpl(new MapWritableStates(Map.of(
                ENTITY_ID_STATE_ID,
                new FunctionWritableSingletonState<>(
                        ENTITY_ID_STATE_ID,
                        ENTITY_ID_STATE_LABEL,
                        () -> EntityNumber.newBuilder().build(),
                        c -> {}),
                ENTITY_COUNTS_STATE_ID,
                new FunctionWritableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID,
                        ENTITY_COUNTS_STATE_LABEL,
                        () -> EntityCounts.newBuilder().build(),
                        c -> {}))));
    }

    @ParameterizedTest
    @CsvSource({
        "20, 15", "9, 14", "10, 15",
    })
    void increaseUnclaimedStartToLargerThanCurrentStakeReward(int amount, int expectedResult) {
        final var state = MapWritableKVState.<EntityNumber, StakingNodeInfo>builder(
                        STAKING_INFOS_STATE_ID, STAKING_INFOS_STATE_LABEL)
                .value(
                        NODE_ID_1,
                        StakingNodeInfo.newBuilder()
                                .nodeNumber(NODE_ID_1.number())
                                .stake(25)
                                .stakeRewardStart(15)
                                .unclaimedStakeRewardStart(5)
                                .build())
                .build();
        infoStore = new WritableStakingInfoStore(
                new MapWritableStates(Map.of(V0490TokenSchema.STAKING_INFOS_STATE_ID, state)), entityIdStore);
        assertUnclaimedStakeRewardStartPrecondition();

        subject.increaseUnclaimedStakeRewards(NODE_ID_1.number(), amount, infoStore);

        final var savedStakeInfo = infoStore.get(NODE_ID_1.number());
        Assertions.assertThat(savedStakeInfo).isNotNull();
        // Case 1: The passed in amount, 20, is greater than the stake reward start, 15, so the unclaimed stake reward
        // value should be the current stake reward start value
        // Case 2: The result should be the stake reward start + the unclaimed stake reward start, 5 + 9 = 14
        // Case 3: Stake reward start + unclaimed stake reward start, 5 + 10 = 15
        Assertions.assertThat(savedStakeInfo.unclaimedStakeRewardStart()).isEqualTo(expectedResult);
    }

    @Test
    void marksNonExistingNodesToDeletedInStateAndAddsNewNodesToState() {
        // State has nodeIds 1, 2, 3
        final var stakingInfosState = new MapWritableKVState.Builder<EntityNumber, StakingNodeInfo>(
                        STAKING_INFOS_STATE_ID, STAKING_INFOS_STATE_LABEL)
                .value(NODE_NUM_1, STAKING_INFO_1)
                .value(NODE_NUM_2, STAKING_INFO_2)
                .value(NODE_NUM_3, STAKING_INFO_3)
                .build();

        final var newStates = newStatesInstance(stakingInfosState);
        infoStore = new WritableStakingInfoStore(newStates, entityIdStore);
        entityIdStore.adjustEntityCount(EntityType.STAKING_INFO, 4L);
        // Platform address book has node Ids 2, 4, 8
        final var networkInfo = new FakeNetworkInfo();

        given(rewardsStore.get()).willReturn(NetworkStakingRewards.DEFAULT);

        // Should update the state to mark node 1 and 3 as deleted
        subject.adjustPostUpgradeStakes(networkInfo, DEFAULT_CONFIG, infoStore, rewardsStore);
        final var updatedStates = newStates.get(STAKING_INFOS_STATE_ID);
        // marks nodes 1, 2 as deleted
        assertThat(((StakingNodeInfo) updatedStates.get(NODE_NUM_1)).deleted()).isTrue();
        assertThat(((StakingNodeInfo) updatedStates.get(NODE_NUM_2)).deleted()).isFalse();
        assertThat(((StakingNodeInfo) updatedStates.get(NODE_NUM_3)).deleted()).isTrue();
        // Also adds node 4 to the state
        assertThat(((StakingNodeInfo) updatedStates.get(NODE_NUM_4)).deleted()).isFalse();
        assertThat(((StakingNodeInfo) updatedStates.get(NODE_NUM_4)).weight()).isZero();
        assertThat(((StakingNodeInfo) updatedStates.get(NODE_NUM_4)).minStake()).isZero();
        assertThat(((StakingNodeInfo) updatedStates.get(NODE_NUM_4)).maxStake()).isEqualTo(45000000000000000L);
        // Also adds node 8 to the state
        assertThat(((StakingNodeInfo) updatedStates.get(NODE_NUM_8)).deleted()).isFalse();
        assertThat(((StakingNodeInfo) updatedStates.get(NODE_NUM_8)).weight()).isZero();
        assertThat(((StakingNodeInfo) updatedStates.get(NODE_NUM_8)).minStake()).isZero();
        assertThat(((StakingNodeInfo) updatedStates.get(NODE_NUM_8)).maxStake()).isEqualTo(45000000000000000L);
    }

    private MapWritableStates newStatesInstance(final MapWritableKVState<EntityNumber, StakingNodeInfo> stakingInfo) {
        //noinspection ReturnOfNull
        return MapWritableStates.builder()
                .state(stakingInfo)
                .state(new FunctionWritableSingletonState<>(
                        STAKING_NETWORK_REWARDS_STATE_ID, STAKING_NETWORK_REWARDS_STATE_LABEL, () -> null, c -> {}))
                .build();
    }

    private void assertUnclaimedStakeRewardStartPrecondition() {
        final var existingStakeInfo = infoStore.get(NODE_ID_1.number());
        Assertions.assertThat(existingStakeInfo).isNotNull();
        Assertions.assertThat(existingStakeInfo.unclaimedStakeRewardStart()).isEqualTo(5);
    }
}
