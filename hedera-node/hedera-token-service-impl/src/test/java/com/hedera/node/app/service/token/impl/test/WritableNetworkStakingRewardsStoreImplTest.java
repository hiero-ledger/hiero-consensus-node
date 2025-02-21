/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.node.app.service.token.TokenService;
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
                new AtomicReference<>(new NetworkStakingRewards(true, 1L, 2L, 3L));
        stakingRewardsState = new FunctionWritableSingletonState<>(
                TokenService.NAME, STAKING_NETWORK_REWARDS_KEY, backingValue::get, backingValue::set);
        given(states.getSingleton(STAKING_NETWORK_REWARDS_KEY))
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
