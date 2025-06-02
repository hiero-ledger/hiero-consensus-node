// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.swirlds.state.test.fixtures.merkle.MerkleTestBase;
import com.swirlds.virtualmap.VirtualMap;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OnDiskReadableStateTest extends MerkleTestBase {
    @BeforeEach
    void setUp() {
        setupFruitVirtualMap();
    }

    private void add(String key, String value) {
        add(fruitVirtualMap, onDiskKeyClassId(), STRING_CODEC, onDiskValueClassId(), STRING_CODEC, key, value);
    }

    private static long onDiskValueClassId() {
        return onDiskValueClassId(FRUIT_STATE_KEY);
    }

    private static long onDiskKeyClassId() {
        return onDiskKeyClassId(FRUIT_STATE_KEY);
    }

    @Nested
    @DisplayName("Constructor Tests")
    final class ConstructorTest {

        @Test
        @DisplayName("You must specify the metadata")
        void nullStateKeyThrows() {
            //noinspection DataFlowIssue
            assertThatThrownBy(
                            () -> new OnDiskReadableKVState<>(null, onDiskKeyClassId(), STRING_CODEC, fruitVirtualMap))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("You must specify the virtual map")
        void nullVirtualMapThrows() {
            //noinspection DataFlowIssue
            assertThatThrownBy(
                            () -> new OnDiskReadableKVState<>(FRUIT_STATE_KEY, onDiskKeyClassId(), STRING_CODEC, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("The stateKey matches that supplied")
        void stateKey() {
            final var state =
                    new OnDiskReadableKVState<>(FRUIT_STATE_KEY, onDiskKeyClassId(), STRING_CODEC, fruitVirtualMap);
            assertThat(state.getStateKey()).isEqualTo(FRUIT_STATE_KEY);
        }
    }

    @Nested
    @DisplayName("Query Tests")
    final class QueryTest {
        private OnDiskReadableKVState<String, String> state;

        @BeforeEach
        void setUp() {
            state = new OnDiskReadableKVState<>(FRUIT_STATE_KEY, onDiskKeyClassId(), STRING_CODEC, fruitVirtualMap);
            add(A_KEY, APPLE);
            add(B_KEY, BANANA);
            add(C_KEY, CHERRY);
        }

        @Test
        @DisplayName("Get keys from the virtual map")
        void get() {
            assertThat(state.get(A_KEY)).isEqualTo(APPLE);
            assertThat(state.get(B_KEY)).isEqualTo(BANANA);
            assertThat(state.get(C_KEY)).isEqualTo(CHERRY);
            assertThat(state.get(D_KEY)).isNull();
            assertThat(state.get(E_KEY)).isNull();
            assertThat(state.get(F_KEY)).isNull();
            assertThat(state.get(G_KEY)).isNull();
        }
    }

    @Test
    @DisplayName("The method warm() calls the appropriate method on the virtual map")
    void warm(@Mock VirtualMap<OnDiskKey<String>, OnDiskValue<String>> virtualMapMock) {
        final var state =
                new OnDiskReadableKVState<>(FRUIT_STATE_KEY, onDiskKeyClassId(), STRING_CODEC, virtualMapMock);
        state.warm(A_KEY);
        verify(virtualMapMock).warm(new OnDiskKey<>(onDiskKeyClassId(), STRING_CODEC, A_KEY));
    }

    @AfterEach
    void tearDown() throws IOException {
        fruitVirtualMap.getDataSource().close();
    }
}
