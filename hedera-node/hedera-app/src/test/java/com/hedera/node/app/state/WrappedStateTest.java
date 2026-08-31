// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.swirlds.state.State;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import com.swirlds.state.test.fixtures.StateTestBase;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WrappedStateTest extends StateTestBase {

    private static final String FOOD_SERVICE = "FOOD_SERVICE";

    @Mock
    private State baseState;

    @Mock
    private State otherState;

    private MapWritableStates writableStates;

    @BeforeEach
    void setUp() {
        final var fruitMap = new HashMap<ProtoBytes, ProtoBytes>();
        fruitMap.put(A_KEY, APPLE);
        writableStates = MapWritableStates.builder()
                .state(new MapWritableKVState<>(FRUIT_STATE_ID, FRUIT_STATE_LABEL, fruitMap))
                .build();
        when(baseState.getWritableStates(FOOD_SERVICE)).thenReturn(writableStates);
    }

    @Test
    void adapterLookupsReuseCachedInstances() {
        final var wrap = new WrappedState(baseState);
        final var states = wrap.getWritableStates(FOOD_SERVICE);
        final var fruit = states.get(FRUIT_STATE_ID);
        final var readable = wrap.getReadableStates(FOOD_SERVICE);

        assertThat(wrap.getWritableStates(FOOD_SERVICE)).isSameAs(states);
        assertThat(states.get(FRUIT_STATE_ID)).isSameAs(fruit);
        assertThat(wrap.getReadableStates(FOOD_SERVICE)).isSameAs(readable);
        assertThat(readable.get(FRUIT_STATE_ID)).isSameAs(readable.get(FRUIT_STATE_ID));
    }

    @Test
    void resetDropsUncommittedMutationsAndKeepsAdapters() {
        final var wrap = new WrappedState(baseState);
        final var states = wrap.getWritableStates(FOOD_SERVICE);
        final var fruit = states.get(FRUIT_STATE_ID);
        final var readable = wrap.getReadableStates(FOOD_SERVICE);
        fruit.put(A_KEY, ACAI);

        wrap.resetForDelegate(baseState);

        assertThat(wrap.getWritableStates(FOOD_SERVICE)).isSameAs(states);
        assertThat(states.get(FRUIT_STATE_ID)).isSameAs(fruit);
        assertThat(wrap.getReadableStates(FOOD_SERVICE)).isSameAs(readable);
        assertThat((ProtoBytes) fruit.get(A_KEY)).isEqualTo(APPLE);
        assertThat(writableStates.get(FRUIT_STATE_ID).get(A_KEY)).isEqualTo(APPLE);
    }

    @Test
    void resetAfterCommitKeepsCommittedValue() {
        final var wrap = new WrappedState(baseState);
        final var fruit = wrap.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID);
        fruit.put(A_KEY, ACAI);
        wrap.commit();

        wrap.resetForDelegate(baseState);

        assertThat((ProtoBytes) fruit.get(A_KEY)).isEqualTo(ACAI);
        assertThat(writableStates.get(FRUIT_STATE_ID).get(A_KEY)).isEqualTo(ACAI);
    }

    @Test
    void resetForDifferentStateDropsAdapters() {
        final var otherFruit = new HashMap<ProtoBytes, ProtoBytes>();
        otherFruit.put(A_KEY, BANANA);
        when(otherState.getWritableStates(FOOD_SERVICE))
                .thenReturn(MapWritableStates.builder()
                        .state(new MapWritableKVState<>(FRUIT_STATE_ID, FRUIT_STATE_LABEL, otherFruit))
                        .build());

        final var wrap = new WrappedState(baseState);
        final var states = wrap.getWritableStates(FOOD_SERVICE);
        states.get(FRUIT_STATE_ID).put(A_KEY, ACAI);

        wrap.resetForDelegate(otherState);

        final var rebound = wrap.getWritableStates(FOOD_SERVICE);
        assertThat(rebound).isNotSameAs(states);
        assertThat((ProtoBytes) rebound.get(FRUIT_STATE_ID).get(A_KEY)).isEqualTo(BANANA);
    }
}
