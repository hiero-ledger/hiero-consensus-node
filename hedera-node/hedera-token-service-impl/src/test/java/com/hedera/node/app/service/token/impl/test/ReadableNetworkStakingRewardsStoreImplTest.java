// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.node.app.service.token.impl.ReadableNetworkStakingRewardsStoreImpl;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableNetworkStakingRewardsStoreImplTest {

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ReadableStates states;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ReadableSingletonState stakingRewardsState;

    private ReadableNetworkStakingRewardsStoreImpl subject;

    @BeforeEach
    void setUp() {
        given(states.getSingleton(STAKING_NETWORK_REWARDS_STATE_ID)).willReturn(stakingRewardsState);
        given(stakingRewardsState.get()).willReturn(new NetworkStakingRewards(true, 1L, 2L, 3L, Timestamp.DEFAULT));
        subject = new ReadableNetworkStakingRewardsStoreImpl(states);
    }

    @Test
    void testNullConstructorArgs() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new ReadableNetworkStakingRewardsStoreImpl(null));
    }

    @Test
    void gettersWork() {
        assertThat(subject.isStakingRewardsActivated()).isTrue();
        assertThat(subject.totalStakeRewardStart()).isEqualTo(1L);
        assertThat(subject.totalStakedStart()).isEqualTo(2L);
        assertThat(subject.pendingRewards()).isEqualTo(3L);
    }
}
