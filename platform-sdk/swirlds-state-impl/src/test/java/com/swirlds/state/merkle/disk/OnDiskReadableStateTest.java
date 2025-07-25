// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.state.merkle.StateUtils;
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

    private void add(String serviceName, String stateKey, ProtoBytes key, ProtoBytes value) {
        addKvState(fruitVirtualMap, serviceName, stateKey, key, value);
    }

    @Nested
    @DisplayName("Constructor Tests")
    final class ConstructorTest {

        @Test
        @DisplayName("You must specify the serviceName")
        void nullServiceNameThrows() {
            assertThatThrownBy(() -> new OnDiskReadableKVState<>(null, FRUIT_STATE_KEY, STRING_CODEC, fruitVirtualMap))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("You must specify the stateKey")
        void nullStateKeyThrows() {
            assertThatThrownBy(
                            () -> new OnDiskReadableKVState<>(FRUIT_SERVICE_NAME, null, STRING_CODEC, fruitVirtualMap))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("You must specify the virtual map")
        void nullVirtualMapThrows() {
            assertThatThrownBy(
                            () -> new OnDiskReadableKVState<>(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, STRING_CODEC, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("The serviceName matches that supplied")
        void serviceName() {
            final var state =
                    new OnDiskReadableKVState<>(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, STRING_CODEC, fruitVirtualMap);
            assertThat(state.getServiceName()).isEqualTo(FRUIT_SERVICE_NAME);
        }

        @Test
        @DisplayName("The stateKey matches that supplied")
        void stateKey() {
            final var state =
                    new OnDiskReadableKVState<>(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, STRING_CODEC, fruitVirtualMap);
            assertThat(state.getStateKey()).isEqualTo(FRUIT_STATE_KEY);
        }
    }

    @Nested
    @DisplayName("Query Tests")
    final class QueryTest {
        private OnDiskReadableKVState<ProtoBytes, ProtoBytes> state;

        @BeforeEach
        void setUp() {
            state = new OnDiskReadableKVState<>(
                    FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, ProtoBytes.PROTOBUF, fruitVirtualMap);
            add(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, A_KEY, APPLE);
            add(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, B_KEY, BANANA);
            add(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, C_KEY, CHERRY);
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
    void warm(@Mock VirtualMap virtualMapMock) {
        final var state =
                new OnDiskReadableKVState<>(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, ProtoBytes.PROTOBUF, virtualMapMock);
        state.warm(A_KEY);
        verify(virtualMapMock).warm(StateUtils.getVirtualMapKeyForKv(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, A_KEY));
    }

    @AfterEach
    void tearDown() throws IOException {
        fruitVirtualMap.getDataSource().close();
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }
}
