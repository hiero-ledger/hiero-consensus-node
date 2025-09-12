// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_LABEL;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFOS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFOS_STATE_LABEL;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WritableStakingInfoStore}.
 */
public class WritableStakingInfoStoreImplTest {
    /**
     * Node ID 1.
     */
    public static final EntityNumber NODE_ID_1 =
            EntityNumber.newBuilder().number(1L).build();

    private WritableStakingInfoStore subject;

    private WritableEntityIdStore entityIdStore;

    @BeforeEach
    void setUp() {
        final var wrappedState = MapWritableKVState.<EntityNumber, StakingNodeInfo>builder(
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
        entityIdStore = new WritableEntityIdStore(new MapWritableStates(Map.of(
                ENTITY_ID_STATE_ID,
                new FunctionWritableSingletonState<>(ENTITY_ID_STATE_ID, ENTITY_ID_STATE_LABEL, () -> null, c -> {}),
                ENTITY_COUNTS_STATE_ID,
                new FunctionWritableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID, ENTITY_COUNTS_STATE_LABEL, () -> null, c -> {}))));
        subject = new WritableStakingInfoStore(
                new MapWritableStates(Map.of(STAKING_INFOS_STATE_ID, wrappedState)), entityIdStore);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void constructorWithNullArg() {
        Assertions.assertThatThrownBy(() -> new WritableStakingInfoStore(null, entityIdStore))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorWithNonNullArg() {
        Assertions.assertThatCode(() -> new WritableStakingInfoStore(mock(WritableStates.class), entityIdStore))
                .doesNotThrowAnyException();
    }

    @Test
    void getNodeIdNotFound() {
        Assertions.assertThat(subject.get(-1)).isNull();
        Assertions.assertThat(subject.get(NODE_ID_1.number() + 1)).isNull();
    }

    @Test
    void getInfoFound() {
        Assertions.assertThat(subject.get(NODE_ID_1.number())).isNotNull().isInstanceOf(StakingNodeInfo.class);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void putWithNullArg() {
        Assertions.assertThatThrownBy(() -> subject.put(NODE_ID_1.number() + 1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void putSuccess() {
        final var newNodeId = NODE_ID_1.number() + 1;
        final var newStakingInfo =
                StakingNodeInfo.newBuilder().nodeNumber(newNodeId).stake(20).build();
        subject.put(newNodeId, newStakingInfo);

        Assertions.assertThat(subject.get(2)).isEqualTo(newStakingInfo);
    }
}
