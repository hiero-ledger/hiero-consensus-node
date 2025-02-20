// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFO_KEY;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.service.token.impl.ReadableStakingInfoStoreImpl;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableStakingInfoStoreImplTest {
    private static final EntityNumber
            NODE_ID_10 = EntityNumber.newBuilder().number(10L).build(),
            NODE_ID_20 = EntityNumber.newBuilder().number(20L).build();

    @Mock
    private ReadableStates states;

    @Mock
    private StakingNodeInfo stakingNodeInfo;

    private ReadableStakingInfoStoreImpl subject;

    @BeforeEach
    void setUp() {
        final var readableStakingNodes = MapReadableKVState.<EntityNumber, StakingNodeInfo>builder(STAKING_INFO_KEY)
                .value(NODE_ID_10, stakingNodeInfo)
                .build();
        given(states.<EntityNumber, StakingNodeInfo>get(STAKING_INFO_KEY)).willReturn(readableStakingNodes);

        subject = new ReadableStakingInfoStoreImpl(states);
    }

    @Test
    void testNullConstructorArgs() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new ReadableStakingInfoStoreImpl(null));
    }

    @Test
    void testGet() {
        final var result = subject.get(NODE_ID_10.number());
        Assertions.assertThat(result).isEqualTo(stakingNodeInfo);
    }

    @Test
    void testGetEmpty() {
        final var result = subject.get(NODE_ID_20.number());
        Assertions.assertThat(result).isNull();
    }

    @Test
    void getAllReturnsAllKeys() {
        final var readableStakingNodes = MapReadableKVState.<EntityNumber, StakingNodeInfo>builder(STAKING_INFO_KEY)
                .value(NODE_ID_10, stakingNodeInfo)
                .value(NODE_ID_20, mock(StakingNodeInfo.class))
                .build();
        given(states.<EntityNumber, StakingNodeInfo>get(STAKING_INFO_KEY)).willReturn(readableStakingNodes);
        subject = new ReadableStakingInfoStoreImpl(states);

        final var result = subject.getAll();
        Assertions.assertThat(result).containsExactlyInAnyOrder(NODE_ID_10.number(), NODE_ID_20.number());
    }

    @Test
    void getAllReturnsEmptyKeys() {
        final var readableStakingNodes = MapReadableKVState.<EntityNumber, StakingNodeInfo>builder(STAKING_INFO_KEY)
                .build(); // Intentionally empty
        given(states.<EntityNumber, StakingNodeInfo>get(STAKING_INFO_KEY)).willReturn(readableStakingNodes);
        subject = new ReadableStakingInfoStoreImpl(states);

        final var result = subject.getAll();
        Assertions.assertThat(result).isEmpty();
    }
}
