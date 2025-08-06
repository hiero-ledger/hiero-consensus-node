// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_KEY;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
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
                        TokenService.NAME, V0490TokenSchema.STAKING_INFO_KEY)
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
                ENTITY_ID_STATE_KEY,
                new FunctionWritableSingletonState<>(EntityIdService.NAME, ENTITY_ID_STATE_KEY, () -> null, c -> {}),
                ENTITY_COUNTS_KEY,
                new FunctionWritableSingletonState<>(EntityIdService.NAME, ENTITY_COUNTS_KEY, () -> null, c -> {}))));
        subject = new WritableStakingInfoStore(
                new MapWritableStates(Map.of(V0490TokenSchema.STAKING_INFO_KEY, wrappedState)), entityIdStore);
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
