// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_LABEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.node.app.service.token.impl.WritableNetworkStakingRewardsStore;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableNetworkStakingRewardsStoreImplTest {

    @Mock(strictness = Mock.Strictness.LENIENT)
    private WritableStates states;

    private WritableSingletonStateBase<NetworkStakingRewards> stakingRewardsState;
    private WritableNetworkStakingRewardsStore subject;

    @BeforeEach
    void setUp() {
        final AtomicReference<NetworkStakingRewards> backingValue =
                new AtomicReference<>(new NetworkStakingRewards(true, 1L, 2L, 3L, Timestamp.DEFAULT));
        stakingRewardsState = new FunctionWritableSingletonState<>(
                STAKING_NETWORK_REWARDS_STATE_ID,
                STAKING_NETWORK_REWARDS_STATE_LABEL,
                backingValue::get,
                backingValue::set);
        given(states.getSingleton(STAKING_NETWORK_REWARDS_STATE_ID))
                .willReturn((WritableSingletonState) stakingRewardsState);

        subject = new WritableNetworkStakingRewardsStore(states);
    }

    @Test
    void constructorWithNullArg() {
        Assertions.assertThatThrownBy(() -> new WritableNetworkStakingRewardsStore(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void putWithNullArg() {
        Assertions.assertThatThrownBy(() -> subject.put(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void putSucceeds() {
        final var newRewards = NetworkStakingRewards.newBuilder()
                .stakingRewardsActivated(false)
                .totalStakedRewardStart(100L)
                .totalStakedStart(200L)
                .pendingRewards(50L)
                .build();
        subject.put(newRewards);

        assertThat(subject.isStakingRewardsActivated()).isFalse();
        assertThat(subject.totalStakeRewardStart()).isEqualTo(100L);
        assertThat(subject.totalStakedStart()).isEqualTo(200L);
        assertThat(subject.pendingRewards()).isEqualTo(50L);
    }
}
