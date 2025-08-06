// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.state.merkle.StateUtils;
import com.swirlds.state.test.fixtures.merkle.MerkleTestBase;
import com.swirlds.virtualmap.VirtualMap;
import java.io.IOException;
import java.util.Spliterators;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OnDiskWritableStateTest extends MerkleTestBase {

    @Nested
    @DisplayName("Constructor Tests")
    final class ConstructorTest {
        @BeforeEach
        void setUp() {
            setupFruitVirtualMap();
        }

        @Test
        @DisplayName("You must specify the serviceName")
        void nullServiceNameThrows() {
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> new OnDiskWritableKVState<>(null, FRUIT_STATE_KEY, STRING_CODEC, fruitVirtualMap))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("You must specify the stateKey")
        void nullStateKeyThrows() {
            //noinspection DataFlowIssue
            assertThatThrownBy(
                            () -> new OnDiskWritableKVState<>(FRUIT_SERVICE_NAME, null, STRING_CODEC, fruitVirtualMap))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("You must specify the virtual map")
        void nullMerkleMapThrows() {
            //noinspection DataFlowIssue
            assertThatThrownBy(
                            () -> new OnDiskWritableKVState<>(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, STRING_CODEC, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("The serviceName matches that supplied by the metadata")
        void serviceName() {
            final var state =
                    new OnDiskWritableKVState<>(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, STRING_CODEC, fruitVirtualMap);
            assertThat(state.getServiceName()).isEqualTo(FRUIT_SERVICE_NAME);
        }

        @Test
        @DisplayName("The stateKey matches that supplied by the metadata")
        void stateKey() {
            final var state =
                    new OnDiskWritableKVState<>(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, STRING_CODEC, fruitVirtualMap);
            assertThat(state.getStateKey()).isEqualTo(FRUIT_STATE_KEY);
        }

        @AfterEach
        void tearDown() throws IOException {
            if (fruitVirtualMap != null && fruitVirtualMap.getReservationCount() > -1) {
                fruitVirtualMap.release();
            }

            fruitVirtualMap.getDataSource().close();
            MerkleDbTestUtils.assertAllDatabasesClosed();
        }
    }

    private void add(String serviceName, String stateKey, ProtoBytes key, ProtoBytes value) {
        addKvState(fruitVirtualMap, serviceName, stateKey, key, value);
    }

    @Nested
    @DisplayName("Query Tests")
    final class QueryTest {
        private OnDiskWritableKVState<ProtoBytes, ProtoBytes> state;

        @BeforeEach
        void setUp() {
            setupFruitVirtualMap();
            state = new OnDiskWritableKVState<>(
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

        @Test
        @DisplayName("Iteration includes both mutations and committed state")
        void iterateIncludesMutations() {
            add(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, A_KEY, APPLE);
            add(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, B_KEY, BANANA);
            state.put(C_KEY, toProtoBytes("Cherry"));
            final var actual = StreamSupport.stream(Spliterators.spliterator(state.keys(), 3, 0), false)
                    .toList();
            assertThat(actual).containsExactlyInAnyOrder(A_KEY, B_KEY, C_KEY);
        }

        @AfterEach
        void tearDown() throws IOException {
            if (fruitVirtualMap != null && fruitVirtualMap.getReservationCount() > -1) {
                fruitVirtualMap.release();
            }

            fruitVirtualMap.getDataSource().close();
        }
    }

    @Nested
    @DisplayName("Mutation Tests")
    final class MutationTest {
        private OnDiskWritableKVState<ProtoBytes, ProtoBytes> state;

        @BeforeEach
        void setUp() {
            setupFruitVirtualMap();
            state = new OnDiskWritableKVState<>(
                    FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, ProtoBytes.PROTOBUF, fruitVirtualMap);
            add(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, A_KEY, APPLE);
            add(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, B_KEY, BANANA);
        }

        boolean merkleMapContainsKey(ProtoBytes key) {
            final Bytes keyBytes = StateUtils.getStateKeyForKv(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, key);
            return fruitVirtualMap.containsKey(keyBytes);
        }

        ProtoBytes readValueFromMerkleMap(ProtoBytes key) {
            final Bytes keyBytes = StateUtils.getStateKeyForKv(FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, key);
            final StateValue stateValue = fruitVirtualMap.get(keyBytes, StateValue.PROTOBUF);
            return stateValue != null ? stateValue.value().as() : null;
        }

        @Test
        @DisplayName("Put a new entry and commit it")
        void putAndCommit() {
            // Make sure this key is NOT in the virtual map
            assertThat(merkleMapContainsKey(E_KEY)).isFalse();

            // Put the value into the state
            state.put(E_KEY, EGGPLANT);

            // Verify it is STILL not in the virtual map
            assertThat(merkleMapContainsKey(E_KEY)).isFalse();

            // Commit it and verify that it IS now in the virtual map
            state.commit();
            assertThat(merkleMapContainsKey(E_KEY)).isTrue();
        }

        @Test
        @DisplayName("Put a new entry and roll it back")
        void putAndReset() {
            // Make sure this key is NOT in the virtual map
            assertThat(merkleMapContainsKey(E_KEY)).isFalse();

            // Put the value into the state
            state.put(E_KEY, EGGPLANT);

            // Verify it is STILL not in the virtual map
            assertThat(merkleMapContainsKey(E_KEY)).isFalse();

            // Reset the state and verify it is STILL not in the map
            state.reset();
            assertThat(merkleMapContainsKey(E_KEY)).isFalse();

            // Now commit it, and verify it is STILL not in the map because the
            // modification was lost after reset
            state.commit();
            assertThat(merkleMapContainsKey(E_KEY)).isFalse();
        }

        @Test
        @DisplayName("Remove an entry and commit it")
        void removeAndCommit() {
            // Make sure this key IS in the virtual map
            assertThat(merkleMapContainsKey(A_KEY)).isTrue();

            // Remove the value from the state
            state.remove(A_KEY);

            // Verify it is STILL in the virtual map
            assertThat(merkleMapContainsKey(A_KEY)).isTrue();

            // Commit it and verify that it is now NOT in the virtual map
            state.commit();
            assertThat(merkleMapContainsKey(A_KEY)).isFalse();
        }

        @Test
        @DisplayName("Remove an entry and roll back the change")
        void removeAndRollback() {
            // Make sure this key IS in the virtual map
            assertThat(merkleMapContainsKey(B_KEY)).isTrue();

            // Remove the value
            state.remove(B_KEY);

            // Verify it is STILL in the virtual map
            assertThat(merkleMapContainsKey(B_KEY)).isTrue();

            // Roll back and confirm it is STILL in the map
            state.reset();
            assertThat(merkleMapContainsKey(B_KEY)).isTrue();

            // Commit it and verify that it is STILL in the map since the change was rolled back
            state.commit();
            assertThat(merkleMapContainsKey(B_KEY)).isTrue();
        }

        /**
         * A variety of modifications over many fast-copies, including rolled-back modifications,
         * with verification that the virtual map has all the right values at each stage in the
         * process.
         */
        @Test
        @DisplayName("The Smörgåsbord of modifications, rollbacks, commits, and fast copies")
        void smorgasbord() throws ParseException {
            //            setupConstructableRegistry();
            // Let's read with get, remove something, put a modification, and put something new
            assertThat(state.get(A_KEY)).isEqualTo(APPLE);
            assertThat(state.get(B_KEY)).isEqualTo(BANANA);
            state.put(C_KEY, CHERRY);
            state.remove(D_KEY);
            state.put(E_KEY, EGGPLANT);
            state.commit();

            // The merkle state should now be:
            assertThat(readValueFromMerkleMap(A_KEY)).isEqualTo(APPLE);
            assertThat(readValueFromMerkleMap(B_KEY)).isEqualTo(BANANA);
            assertThat(readValueFromMerkleMap(C_KEY)).isEqualTo(CHERRY);
            assertThat(readValueFromMerkleMap(D_KEY)).isNull();
            assertThat(readValueFromMerkleMap(E_KEY)).isEqualTo(EGGPLANT);

            // Now let's make a fast copy and create a new state and make some more
            // modifications and reads. And then let's throw them all away and make
            // sure the virtual map hasn't changed.
            final VirtualMap oldVirtualMap = fruitVirtualMap;
            fruitVirtualMap = fruitVirtualMap.copy();
            oldVirtualMap.release();
            state = new OnDiskWritableKVState<>(
                    FRUIT_SERVICE_NAME, FRUIT_STATE_KEY, ProtoBytes.PROTOBUF, fruitVirtualMap);
            assertThat(state.get(A_KEY)).isEqualTo(APPLE);
            state.remove(B_KEY);
            assertThat(state.get(C_KEY)).isEqualTo(CHERRY);
            state.put(D_KEY, DATE);
            state.put(E_KEY, ELDERBERRY);
            state.reset();

            // The merkle state should still be:
            assertThat(readValueFromMerkleMap(A_KEY)).isEqualTo(APPLE);
            assertThat(readValueFromMerkleMap(B_KEY)).isEqualTo(BANANA);
            assertThat(readValueFromMerkleMap(C_KEY)).isEqualTo(CHERRY);
            assertThat(readValueFromMerkleMap(D_KEY)).isNull();
            assertThat(readValueFromMerkleMap(E_KEY)).isEqualTo(EGGPLANT);

            // Now reuse the same state, make some modifications, and commit them.
            state.put(A_KEY, ACAI);
            state.remove(B_KEY);
            state.remove(C_KEY);
            state.put(D_KEY, DATE);
            state.put(E_KEY, ELDERBERRY);
            state.commit();

            // The merkle state should now be:
            assertThat(readValueFromMerkleMap(A_KEY)).isEqualTo(ACAI);
            assertThat(readValueFromMerkleMap(B_KEY)).isNull();
            assertThat(readValueFromMerkleMap(C_KEY)).isNull();
            assertThat(readValueFromMerkleMap(D_KEY)).isEqualTo(DATE);
            assertThat(readValueFromMerkleMap(E_KEY)).isEqualTo(ELDERBERRY);
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        fruitVirtualMap.getDataSource().close();
    }
}
