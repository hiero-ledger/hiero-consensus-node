// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_LABEL;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFOS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFOS_STATE_LABEL;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.service.token.impl.ReadableStakingInfoStoreImpl;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.Map;
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

    private WritableEntityIdStore entityCounters;

    private ReadableStakingInfoStoreImpl subject;

    @BeforeEach
    void setUp() {
        final var readableStakingNodes = MapReadableKVState.<EntityNumber, StakingNodeInfo>builder(
                        STAKING_INFOS_STATE_ID, STAKING_INFOS_STATE_LABEL)
                .value(NODE_ID_10, stakingNodeInfo)
                .build();
        entityCounters = new WritableEntityIdStore(new MapWritableStates(Map.of(
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

        given(states.<EntityNumber, StakingNodeInfo>get(STAKING_INFOS_STATE_ID)).willReturn(readableStakingNodes);

        subject = new ReadableStakingInfoStoreImpl(states, entityCounters);
    }

    @Test
    void testNullConstructorArgs() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new ReadableStakingInfoStoreImpl(null, entityCounters));
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
        final var readableStakingNodes = MapReadableKVState.<EntityNumber, StakingNodeInfo>builder(
                        STAKING_INFOS_STATE_ID, STAKING_INFOS_STATE_LABEL)
                .value(NODE_ID_10, stakingNodeInfo)
                .value(NODE_ID_20, mock(StakingNodeInfo.class))
                .build();
        given(states.<EntityNumber, StakingNodeInfo>get(STAKING_INFOS_STATE_ID)).willReturn(readableStakingNodes);
        entityCounters = new WritableEntityIdStore(new MapWritableStates(Map.of(
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
                        () -> EntityCounts.newBuilder()
                                .numNodes(21)
                                .numStakingInfos(21)
                                .build(),
                        c -> {}))));

        subject = new ReadableStakingInfoStoreImpl(states, entityCounters);

        final var result = subject.getAll();
        Assertions.assertThat(result).containsExactlyInAnyOrder(NODE_ID_10.number(), NODE_ID_20.number());
    }

    @Test
    void getAllReturnsEmptyKeys() {
        final var readableStakingNodes = MapReadableKVState.<EntityNumber, StakingNodeInfo>builder(
                        STAKING_INFOS_STATE_ID, STAKING_INFOS_STATE_LABEL)
                .build(); // Intentionally empty
        given(states.<EntityNumber, StakingNodeInfo>get(STAKING_INFOS_STATE_ID)).willReturn(readableStakingNodes);
        subject = new ReadableStakingInfoStoreImpl(states, entityCounters);

        final var result = subject.getAll();
        Assertions.assertThat(result).isEmpty();
    }
}
