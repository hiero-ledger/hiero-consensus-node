// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertAllDatabasesClosed;
import static com.swirlds.state.StateChangeListener.StateType.MAP;
import static com.swirlds.state.StateChangeListener.StateType.QUEUE;
import static com.swirlds.state.StateChangeListener.StateType.SINGLETON;
import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.swirlds.base.state.MutabilityException;
import com.swirlds.state.StateChangeListener;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.StateTestBase;
import com.swirlds.state.test.fixtures.merkle.MerkleTestBase;
import com.swirlds.state.test.fixtures.merkle.TestVirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import java.io.IOException;
import java.util.EnumSet;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class VirtualMapStateTest extends MerkleTestBase {

    private TestVirtualMapState virtualMapState;

    private static final int GENESIS_ROUND = 0;

    /**
     * Start with an empty Virtual Map State, but with the "fruit" map and metadata created and ready to
     * be added.
     */
    @BeforeEach
    void setUp() {
        virtualMapState = new TestVirtualMapState();
    }

    @Nested
    @DisplayName("Service Registration Tests")
    final class RegistrationTest {
        @Test
        @DisplayName("Adding a null service metadata will throw an NPE")
        void addingNullServiceMetaDataThrows() {
            //noinspection ConstantConditions
            assertThatThrownBy(() -> virtualMapState.initializeState(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Adding a singleton service")
        void addingSingletonService() {
            // Given a singleton
            setupSingletonCountry();
            final int singletonStateId = countryMetadata.stateDefinition().stateId();

            // When added to the state
            virtualMapState.initializeState(countryMetadata);

            // Then we can see it in the state
            assertThat(virtualMapState.getServices().size()).isEqualTo(1);
            assertTrue(virtualMapState.getServices().containsKey(countryMetadata.serviceName()));
            assertDoesNotThrow(() -> virtualMapState
                    .getReadableStates(countryMetadata.serviceName())
                    .getSingleton(singletonStateId));
            assertDoesNotThrow(() -> virtualMapState
                    .getWritableStates(countryMetadata.serviceName())
                    .getSingleton(singletonStateId));
        }

        @Test
        @DisplayName("Adding a queue service")
        void addingQueueService() {
            // Given a queue
            setupSteamQueue();
            final int queueStateId = steamMetadata.stateDefinition().stateId();

            // When added to the state
            virtualMapState.initializeState(steamMetadata);

            // Then we can see it in the state
            assertThat(virtualMapState.getServices().size()).isEqualTo(1);
            assertTrue(virtualMapState.getServices().containsKey(steamMetadata.serviceName()));
            assertDoesNotThrow(() -> virtualMapState
                    .getReadableStates(steamMetadata.serviceName())
                    .getQueue(queueStateId));
            assertDoesNotThrow(() -> virtualMapState
                    .getWritableStates(steamMetadata.serviceName())
                    .getQueue(queueStateId));
        }

        @Test
        @DisplayName("Adding a k/v service")
        void addingKvService() {
            // Given a virtual map
            setupFruitVirtualMap();
            final int kvStateId = fruitMetadata.stateDefinition().stateId();

            // When added to the state
            virtualMapState.initializeState(fruitMetadata);

            // Then we can see it in the state
            assertThat(virtualMapState.getServices().size()).isEqualTo(1);
            assertTrue(virtualMapState.getServices().containsKey(fruitMetadata.serviceName()));
            assertDoesNotThrow(() -> virtualMapState
                    .getReadableStates(fruitMetadata.serviceName())
                    .get(kvStateId));
            assertDoesNotThrow(() -> virtualMapState
                    .getWritableStates(fruitMetadata.serviceName())
                    .get(kvStateId));
        }

        @Test
        @DisplayName("Adding the same service twice with two different metadata replaces the metadata")
        void addingServiceTwiceWithDifferentMetadata() {
            // Given an empty merkle tree, when I add the same node twice but with different
            // metadata,
            setupFruitVirtualMap();
            final var fruitVirtualMetadata2 = new StateMetadata<>(
                    StateTestBase.FIRST_SERVICE,
                    StateDefinition.onDisk(
                            FRUIT_STATE_ID, FRUIT_STATE_KEY, ProtoBytes.PROTOBUF, ProtoBytes.PROTOBUF, 999));

            virtualMapState.initializeState(fruitMetadata);
            virtualMapState.initializeState(fruitVirtualMetadata2);

            // Then the original node is kept and the second node ignored
            assertThat(virtualMapState.getServices().size()).isEqualTo(1);
            assertTrue(virtualMapState.getServices().containsKey(fruitMetadata.serviceName()));
            assertTrue(virtualMapState
                    .getServices()
                    .get(fruitMetadata.serviceName())
                    .containsKey(FRUIT_STATE_ID));
        }
    }

    @Nested
    @DisplayName("Remove Tests")
    final class RemoveTest {
        @Test
        @DisplayName("You cannot remove with a null service name")
        void usingNullServiceNameToRemoveThrows() {
            //noinspection ConstantConditions
            assertThatThrownBy(() -> virtualMapState.removeServiceState(null, StateTestBase.FRUIT_STATE_ID))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Removing an unknown service name does nothing")
        void removeWithUnknownServiceName() {
            // Given a virtual map state with a random service
            setupFruitVirtualMap();
            virtualMapState.initializeState(fruitMetadata);
            final var stateMetadataSize = virtualMapState
                    .getServices()
                    .get(fruitMetadata.serviceName())
                    .size();
            final var writableStatesSize = virtualMapState
                    .getReadableStates(fruitMetadata.serviceName())
                    .size();

            // When you try to remove an unknown service
            virtualMapState.removeServiceState(UNKNOWN_SERVICE, FRUIT_STATE_ID);

            // It has no effect on anything
            assertThat(virtualMapState
                            .getServices()
                            .get(fruitMetadata.serviceName())
                            .size())
                    .isEqualTo(stateMetadataSize);
            assertThat(virtualMapState
                            .getWritableStates(fruitMetadata.serviceName())
                            .size())
                    .isEqualTo(writableStatesSize);
        }

        @Test
        @DisplayName("Removing an unknown state key does nothing")
        void removeWithUnknownStateKey() {
            // Given a virtual map state with a random service
            setupFruitVirtualMap();
            virtualMapState.initializeState(fruitMetadata);
            final var stateMetadataSize = virtualMapState
                    .getServices()
                    .get(fruitMetadata.serviceName())
                    .size();
            final var writableStatesSize = virtualMapState
                    .getWritableStates(fruitMetadata.serviceName())
                    .size();

            // When you try to remove an unknown service
            virtualMapState.removeServiceState(FIRST_SERVICE, UNKNOWN_STATE_ID);

            // It has no effect on anything
            assertThat(virtualMapState
                            .getServices()
                            .get(fruitMetadata.serviceName())
                            .size())
                    .isEqualTo(stateMetadataSize);
            assertThat(virtualMapState
                            .getWritableStates(fruitMetadata.serviceName())
                            .size())
                    .isEqualTo(writableStatesSize);
        }

        @Test
        @DisplayName("Calling `remove` removes the right service")
        void remove() {
            // Given a virtual map state with a first service
            setupFruitVirtualMap();
            virtualMapState.initializeState(fruitMetadata);

            // When you try to remove a first service
            virtualMapState.removeServiceState(FIRST_SERVICE, FRUIT_STATE_ID);

            // First service would be removed
            assertThat(virtualMapState
                            .getServices()
                            .get(fruitMetadata.serviceName())
                            .size())
                    .isEqualTo(0);
            assertThat(virtualMapState
                            .getWritableStates(fruitMetadata.serviceName())
                            .size())
                    .isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("ReadableStates Tests")
    final class ReadableStatesTest {
        @BeforeEach
        void setUp() {
            // calling below setup methods only for metadata init
            // FUTURE WORK: refactor after MerkleStateRootTest will be removed
            setupFruitVirtualMap();
            setupSingletonCountry();
            setupSteamQueue();

            // adding k/v and singleton states directly to the virtual map
            final var virtualMap = (VirtualMap) virtualMapState.getRoot();
            addKvState(virtualMap, fruitMetadata, A_KEY, APPLE);
            addKvState(virtualMap, fruitMetadata, B_KEY, BANANA);
            addSingletonState(virtualMap, countryMetadata, GHANA);

            // adding queue state via State API, to init the QueueState
            virtualMapState.initializeState(steamMetadata);
            final var writableStates = virtualMapState.getWritableStates(FIRST_SERVICE);
            writableStates.getQueue(STEAM_STATE_ID).add(ART);
            ((CommittableWritableStates) writableStates).commit();
        }

        @Test
        @DisplayName("Getting ReadableStates on an unknown service returns an empty entity")
        void unknownServiceNameUsingReadableStates() {
            final var states = virtualMapState.getReadableStates(UNKNOWN_SERVICE);
            assertThat(states).isNotNull();
            assertThat(states.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("Reading an unknown state on ReadableStates should throw IAE")
        void unknownState() {
            // Given a State with the fruit and animal and country states
            virtualMapState.initializeState(fruitMetadata);
            virtualMapState.initializeState(countryMetadata);
            virtualMapState.initializeState(steamMetadata);

            // When we get the ReadableStates
            final var states = virtualMapState.getReadableStates(FIRST_SERVICE);

            // Then query it for an unknown state and get an IAE
            assertThatThrownBy(() -> states.get(UNKNOWN_STATE_ID)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Read a virtual map")
        void readVirtualMap() {
            try {
                // Given a State with the fruit virtual map
                virtualMapState.initializeState(fruitMetadata);

                // When we get the ReadableStates
                final var states = virtualMapState.getReadableStates(FIRST_SERVICE);

                // Then it isn't null
                assertThat(states.get(FRUIT_STATE_ID)).isNotNull();
            } finally {
                fruitVirtualMap.release();
            }
        }

        @Test
        @DisplayName("Contains is true for all states in stateKeys and false for unknown ones")
        void contains() {
            // Given a State with the fruit and animal and country and steam states
            virtualMapState.initializeState(fruitMetadata);
            virtualMapState.initializeState(countryMetadata);
            virtualMapState.initializeState(steamMetadata);

            // When we get the ReadableStates and the state keys
            final var states = virtualMapState.getReadableStates(FIRST_SERVICE);
            final var stateIds = states.stateIds();

            // Then we find "contains" is true for every state in stateIds
            assertThat(stateIds).hasSize(3);
            for (final var stateId : stateIds) {
                assertThat(states.contains(stateId)).isTrue();
            }

            // And we find other nonsense states are false for contains
            assertThat(states.contains(UNKNOWN_STATE_ID)).isFalse();
        }

        @Test
        @DisplayName("Getting the same readable state twice returns the same instance")
        void getReturnsSameInstanceIfCalledTwice() {
            // Given a State with the fruit and the ReadableStates for it
            virtualMapState.initializeState(fruitMetadata);
            final var states = virtualMapState.getReadableStates(FIRST_SERVICE);

            // When we call get twice
            final var kvState1 = states.get(FRUIT_STATE_ID);
            final var kvState2 = states.get(FRUIT_STATE_ID);

            // Then we must find both variables are the same instance
            assertThat(kvState1).isSameAs(kvState2);
        }

        @Test
        @DisplayName("Getting ReadableStates on a known service returns an object with all the state")
        void knownServiceNameUsingReadableStates() {
            // Given a State with the fruit and animal and country states
            virtualMapState.initializeState(fruitMetadata);
            virtualMapState.initializeState(countryMetadata);
            virtualMapState.initializeState(steamMetadata);

            // When we get the ReadableStates
            final var states = virtualMapState.getReadableStates(FIRST_SERVICE);

            // Then query it, we find the data we expected to find
            assertThat(states).isNotNull();
            assertThat(states.isEmpty()).isFalse();
            assertThat(states.size()).isEqualTo(3); // fruit and country and steam

            final ReadableKVState<ProtoBytes, ProtoBytes> fruitState = states.get(FRUIT_STATE_ID);
            assertFruitState(fruitState);

            final ReadableSingletonState<ProtoBytes> countryState = states.getSingleton(COUNTRY_STATE_ID);
            assertCountryState(countryState);

            final ReadableQueueState<ProtoBytes> steamState = states.getQueue(STEAM_STATE_ID);
            assertSteamState(steamState);

            // And the states we got back CANNOT be cast to WritableState
            assertThatThrownBy(
                            () -> { //noinspection rawtypes
                                final var ignored = (WritableKVState) fruitState;
                            })
                    .isInstanceOf(ClassCastException.class);

            assertThatThrownBy(
                            () -> { //noinspection rawtypes
                                final var ignored = (WritableSingletonState) countryState;
                            })
                    .isInstanceOf(ClassCastException.class);

            assertThatThrownBy(
                            () -> { //noinspection rawtypes
                                final var ignored = (WritableQueueState) steamState;
                            })
                    .isInstanceOf(ClassCastException.class);
        }

        private static void assertFruitState(ReadableKVState<ProtoBytes, ProtoBytes> fruitState) {
            assertThat(fruitState).isNotNull();
            assertThat(fruitState.get(A_KEY)).isSameAs(APPLE);
            assertThat(fruitState.get(B_KEY)).isSameAs(BANANA);
            assertThat(fruitState.get(C_KEY)).isNull();
            assertThat(fruitState.get(D_KEY)).isNull();
            assertThat(fruitState.get(E_KEY)).isNull();
            assertThat(fruitState.get(F_KEY)).isNull();
            assertThat(fruitState.get(G_KEY)).isNull();
        }

        private void assertCountryState(ReadableSingletonState<ProtoBytes> countryState) {
            assertThat(countryState.getStateId()).isEqualTo(COUNTRY_STATE_ID);
            assertThat(countryState.get()).isEqualTo(GHANA);
        }

        private void assertSteamState(ReadableQueueState<ProtoBytes> steamState) {
            assertThat(steamState.getStateId()).isEqualTo(STEAM_STATE_ID);
            assertThat(steamState.peek()).isEqualTo(ART);
        }
    }

    @Nested
    @DisplayName("WritableStates Tests")
    final class WritableStatesTest {

        @BeforeEach
        void setUp() {
            // calling below setup methods only for metadata init
            // FUTURE WORK: refactor after MerkleStateRootTest will be removed
            setupFruitVirtualMap();
            setupSingletonCountry();
            setupSteamQueue();

            // adding k/v and singleton states directly to the virtual map
            final var virtualMap = (VirtualMap) virtualMapState.getRoot();
            addKvState(virtualMap, fruitMetadata, A_KEY, APPLE);
            addKvState(virtualMap, fruitMetadata, B_KEY, BANANA);
            addSingletonState(virtualMap, countryMetadata, GHANA);

            // adding queue state via State API, to init the QueueState
            virtualMapState.initializeState(steamMetadata);
            final var writableStates = virtualMapState.getWritableStates(FIRST_SERVICE);
            writableStates.getQueue(STEAM_STATE_ID).add(ART);
            ((CommittableWritableStates) writableStates).commit();
        }

        @Test
        @DisplayName("Getting WritableStates on an unknown service returns an empty entity")
        void unknownServiceNameUsingWritableStates() {
            final var states = virtualMapState.getWritableStates(UNKNOWN_SERVICE);
            assertThat(states).isNotNull();
            assertThat(states.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("Reading an unknown state on WritableState should throw IAE")
        void unknownState() {
            // Given a State with the fruit and animal and country states
            virtualMapState.initializeState(fruitMetadata);
            virtualMapState.initializeState(countryMetadata);
            virtualMapState.initializeState(steamMetadata);

            // When we get the WritableStates
            final var states = virtualMapState.getWritableStates(FIRST_SERVICE);

            // Then query it for an unknown state and get an IAE
            assertThatThrownBy(() -> states.get(UNKNOWN_STATE_ID)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Read a virtual map")
        void readVirtualMap() {
            try {
                // Given a State with the fruit virtual map
                virtualMapState.initializeState(fruitMetadata);

                // When we get the WritableStates
                final var states = virtualMapState.getWritableStates(FIRST_SERVICE);

                // Then it isn't null
                assertThat(states.get(FRUIT_STATE_ID)).isNotNull();
            } finally {
                fruitVirtualMap.release();
            }
        }

        @Test
        @DisplayName("Contains is true for all states in stateKeys and false for unknown ones")
        void contains() {
            // Given a State with the fruit and animal and country states
            virtualMapState.initializeState(fruitMetadata);
            virtualMapState.initializeState(countryMetadata);
            virtualMapState.initializeState(steamMetadata);

            // When we get the WritableStates and the state keys
            final var states = virtualMapState.getWritableStates(FIRST_SERVICE);
            final var stateIds = states.stateIds();

            // Then we find "contains" is true for every state in stateIds
            assertThat(stateIds).hasSize(3);
            for (final var stateId : stateIds) {
                assertThat(states.contains(stateId)).isTrue();
            }

            // And we find other nonsense states are false for contains
            assertThat(states.contains(UNKNOWN_STATE_ID)).isFalse();
        }

        @Test
        @DisplayName("Getting the same writable state twice returns the same instance")
        void getReturnsSameInstanceIfCalledTwice() {
            // Given a State with the fruit and the WritableStates for it
            virtualMapState.initializeState(fruitMetadata);
            final var states = virtualMapState.getWritableStates(FIRST_SERVICE);

            // When we call get twice
            final var kvState1 = states.get(FRUIT_STATE_ID);
            final var kvState2 = states.get(FRUIT_STATE_ID);

            // Then we must find both variables are the same instance
            assertThat(kvState1).isSameAs(kvState2);
        }

        @Test
        @DisplayName("Getting WritableStates on a known service returns an object with all the state")
        void knownServiceNameUsingWritableStates() {
            // Given a State with the fruit and animal and country states
            virtualMapState.initializeState(fruitMetadata);
            virtualMapState.initializeState(countryMetadata);
            virtualMapState.initializeState(steamMetadata);

            // When we get the WritableStates
            final var states = virtualMapState.getWritableStates(FIRST_SERVICE);

            // We find the data we expected to find
            assertThat(states).isNotNull();
            assertThat(states.isEmpty()).isFalse();
            assertThat(states.size()).isEqualTo(3);

            final WritableKVState<ProtoBytes, ProtoBytes> fruitStates = states.get(FRUIT_STATE_ID);
            assertThat(fruitStates).isNotNull();

            final var countryState = states.getSingleton(COUNTRY_STATE_ID);
            assertThat(countryState).isNotNull();

            final var steamState = states.getQueue(STEAM_STATE_ID);
            assertThat(steamState).isNotNull();

            // And the states we got back are writable
            fruitStates.put(C_KEY, CHERRY);
            assertThat(fruitStates.get(C_KEY)).isSameAs(CHERRY);
            countryState.put(ESTONIA);
            assertThat(countryState.get()).isEqualTo(ESTONIA);
        }
    }

    @Nested
    @DisplayName("Copy Tests")
    final class CopyTest {
        @Test
        @DisplayName("Cannot call copy on original after copy")
        void callCopyTwiceOnOriginalThrows() {
            final var stateRootCopy = virtualMapState.copy();
            assertThatThrownBy(virtualMapState::copy).isInstanceOf(MutabilityException.class);
            stateRootCopy.release();
        }

        @Test
        @DisplayName("Cannot call putServiceStateIfAbsent on original after copy")
        void addServiceOnOriginalAfterCopyThrows() {
            setupFruitVirtualMap();
            final var stateRootCopy = virtualMapState.copy();
            assertThatThrownBy(() -> virtualMapState.initializeState(fruitMetadata))
                    .isInstanceOf(MutabilityException.class);
            stateRootCopy.release();
        }

        @Test
        @DisplayName("Cannot call removeServiceState on original after copy")
        void removeServiceOnOriginalAfterCopyThrows() {
            setupFruitVirtualMap();
            virtualMapState.initializeState(fruitMetadata);
            final var stateRootCopy = virtualMapState.copy();
            assertThatThrownBy(() -> virtualMapState.removeServiceState(FIRST_SERVICE, FRUIT_STATE_ID))
                    .isInstanceOf(MutabilityException.class);
            stateRootCopy.release();
        }

        @Test
        @DisplayName("Cannot call createWritableStates on original after copy")
        void createWritableStatesOnOriginalAfterCopyThrows() {
            final var stateRootCopy = virtualMapState.copy();
            assertThatThrownBy(() -> virtualMapState.getWritableStates(FRUIT_STATE_KEY))
                    .isInstanceOf(MutabilityException.class);
            stateRootCopy.release();
        }
    }

    @Nested
    @DisplayName("with registered listeners")
    class WithRegisteredListeners {
        @Mock
        private StateChangeListener kvListener;

        @Mock
        private StateChangeListener singletonListener;

        @Mock
        private StateChangeListener queueListener;

        @BeforeEach
        void setUp() {
            given(kvListener.stateTypes()).willReturn(EnumSet.of(MAP));
            given(singletonListener.stateTypes()).willReturn(EnumSet.of(SINGLETON));
            given(queueListener.stateTypes()).willReturn(EnumSet.of(QUEUE));

            // calling below setup methods only for metadata init
            // FUTURE WORK: refactor after MerkleStateRootTest will be removed
            setupFruitVirtualMap();
            setupSingletonCountry();
            setupSteamQueue();

            // adding k/v and singleton states directly to the virtual map
            final var virtualMap = (VirtualMap) virtualMapState.getRoot();
            addKvState(fruitVirtualMap, fruitMetadata, C_KEY, CHERRY);
            addSingletonState(virtualMap, countryMetadata, FRANCE);

            // adding queue state via State API, to init the QueueState
            virtualMapState.initializeState(steamMetadata);
            final var writableStates = virtualMapState.getWritableStates(FIRST_SERVICE);
            writableStates.getQueue(STEAM_STATE_ID).add(ART);
            ((CommittableWritableStates) writableStates).commit();
        }

        @Test
        void appropriateListenersAreInvokedOnCommit() {
            virtualMapState.initializeState(fruitMetadata);
            virtualMapState.initializeState(countryMetadata);
            virtualMapState.initializeState(steamMetadata);

            virtualMapState.registerCommitListener(kvListener);
            virtualMapState.registerCommitListener(singletonListener);
            virtualMapState.registerCommitListener(queueListener);

            final var states = virtualMapState.getWritableStates(FIRST_SERVICE);
            final var fruitState = states.get(FRUIT_STATE_ID);
            final var countryState = states.getSingleton(COUNTRY_STATE_ID);
            final var steamState = states.getQueue(STEAM_STATE_ID);

            fruitState.put(E_KEY, EGGPLANT);
            fruitState.remove(C_KEY);
            countryState.put(ESTONIA);
            steamState.poll();
            steamState.add(BIOLOGY);

            ((CommittableWritableStates) states).commit();

            verify(kvListener).mapUpdateChange(FRUIT_STATE_ID, E_KEY, EGGPLANT);
            verify(kvListener).mapDeleteChange(FRUIT_STATE_ID, C_KEY);
            verify(singletonListener).singletonUpdateChange(COUNTRY_STATE_ID, ESTONIA);
            verify(queueListener).queuePushChange(STEAM_STATE_ID, BIOLOGY);
            verify(queueListener).queuePopChange(STEAM_STATE_ID);

            verifyNoMoreInteractions(kvListener);
            verifyNoMoreInteractions(singletonListener);
            verifyNoMoreInteractions(queueListener);
        }

        @AfterEach
        void tearDown() {
            fruitVirtualMap.release();
        }
    }

    @Nested
    @DisplayName("Hashing test")
    class HashingTest {

        @BeforeEach
        void setUp() {
            // calling below setup methods only for metadata init
            // FUTURE WORK: refactor after MerkleStateRootTest will be removed
            setupFruitVirtualMap();
            setupSingletonCountry();
            setupSteamQueue();

            // adding k/v and singleton states directly to the virtual map
            final var virtualMap = (VirtualMap) virtualMapState.getRoot();
            addKvState(fruitVirtualMap, fruitMetadata, A_KEY, APPLE);
            addKvState(fruitVirtualMap, fruitMetadata, B_KEY, BANANA);
            addSingletonState(virtualMap, countryMetadata, GHANA);

            // Given a State with the fruit and animal and country states
            virtualMapState.initializeState(fruitMetadata);
            virtualMapState.initializeState(countryMetadata);
            virtualMapState.initializeState(steamMetadata);
            // adding queue state via State API, to init the QueueState
            final var writableStates = virtualMapState.getWritableStates(FIRST_SERVICE);
            writableStates.getQueue(STEAM_STATE_ID).add(ART);
            ((CommittableWritableStates) writableStates).commit();
        }

        @Test
        @DisplayName("Calling getHash will perform hashing if needed")
        void hashByDefault() {
            assertNotNull(virtualMapState.getHash());
        }

        @Test
        @DisplayName("computeHash is doesn't work on mutable states")
        void calculateHashOnMutable() {
            assertThrows(IllegalStateException.class, virtualMapState::computeHash);
        }

        @Test
        @DisplayName("Hash is computed after computeHash invocation")
        void calculateHash() {
            final var stateRootCopy = virtualMapState.copy();
            virtualMapState.computeHash();
            assertNotNull(virtualMapState.getHash());
            stateRootCopy.release();
        }

        @Test
        @DisplayName("computeHash is idempotent")
        void calculateHash_idempotent() {
            final var stateRootCopy = virtualMapState.copy();
            virtualMapState.computeHash();
            Hash hash1 = virtualMapState.getHash();
            virtualMapState.computeHash();
            Hash hash2 = virtualMapState.getHash();
            assertSame(hash1, hash2);
            stateRootCopy.release();
        }
    }

    @Nested
    @DisplayName("Path lookup tests")
    class PathLookupTest {

        private VirtualMap virtualMap;

        @BeforeEach
        void setUp() {
            // Initialize metadata for all relevant states
            setupFruitVirtualMap();
            setupSingletonCountry();
            setupSteamQueue();
            virtualMap = (VirtualMap) virtualMapState.getRoot();
            virtualMapState.initializeState(steamMetadata);
            virtualMapState.initializeState(countryMetadata);
            virtualMapState.initializeState(fruitMetadata);

            addSingletonState(virtualMap, countryMetadata, GHANA);
            addKvState(virtualMap, fruitMetadata, A_KEY, APPLE);
            // Initialize a queue state and add three elements via the API to ensure QueueState is set up
            final WritableStates writable = virtualMapState.getWritableStates(FIRST_SERVICE);
            final WritableQueueState<ProtoBytes> queue = writable.getQueue(STEAM_STATE_ID);
            queue.add(ART);
            queue.add(BIOLOGY);
            queue.add(CHEMISTRY);
            ((CommittableWritableStates) writable).commit();
        }

        @Test
        @DisplayName("singletonPath returns path for existing singleton")
        void singletonPath_found() {

            // Expected path using records.findPath on the singleton key
            final long expected = ((VirtualMap) virtualMapState.getRoot())
                    .getRecords()
                    .findPath(StateUtils.getStateKeyForSingleton(COUNTRY_STATE_ID));

            final long actual = virtualMapState.singletonPath(COUNTRY_STATE_ID);
            assertThat(actual).isEqualTo(expected);
        }

        @Test
        @DisplayName("singletonPath returns path for existing singleton after state update")
        void singletonPath_found_after_update() {
            // releasing pre-created test fixtures, we're not going to need them
            virtualMapState.release();
            fruitVirtualMap.release();

            // create and prepare a new state
            virtualMapState = new TestVirtualMapState();
            setupFruitVirtualMap();
            setupSingletonCountry();
            virtualMap = (VirtualMap) virtualMapState.getRoot();
            virtualMapState.initializeState(steamMetadata);

            addSingletonState(virtualMap, countryMetadata, GHANA);

            final long initialPath = ((VirtualMap) virtualMapState.getRoot())
                    .getRecords()
                    .findPath(StateUtils.getStateKeyForSingleton(COUNTRY_STATE_ID));
            assertThat(initialPath).isEqualTo(1);

            addKvState(virtualMap, fruitMetadata, B_KEY, BANANA);
            addKvState(virtualMap, fruitMetadata, C_KEY, CHERRY);
            ((CommittableWritableStates) virtualMapState.getWritableStates(FIRST_SERVICE)).commit();

            // after the update of the state we expect the path of the singleton to be updated as well
            final long actual = virtualMapState.singletonPath(COUNTRY_STATE_ID);
            assertThat(actual).isEqualTo(initialPath + 2);
        }

        @Test
        @DisplayName("singletonPath returns invalid path for unkown singleton state ID")
        void singletonPath_unknownState() {
            final long actual = virtualMapState.singletonPath(rand.nextInt(65535));
            assertThat(actual).isEqualTo(INVALID_PATH);
        }

        @Test
        @DisplayName("kvPath returns path for existing kv key")
        void kvPath_found() {
            final var kvKey = StateUtils.getStateKeyForKv(FRUIT_STATE_ID, A_KEY, ProtoBytes.PROTOBUF);
            final long expected =
                    ((VirtualMap) virtualMapState.getRoot()).getRecords().findPath(kvKey);

            final long actualForBytes = virtualMapState.kvPath(FRUIT_STATE_ID, ProtoBytes.PROTOBUF.toBytes(A_KEY));
            final long actualForObj = virtualMapState.kvPath(FRUIT_STATE_ID, A_KEY, ProtoBytes.PROTOBUF);
            assertThat(actualForBytes).isEqualTo(expected);
            assertThat(actualForObj).isEqualTo(expected);
        }

        @Test
        @DisplayName("kvPath lookup for non-existing key")
        void kvPath_notFound() {
            final long actual = virtualMapState.kvPath(FRUIT_STATE_ID, ProtoBytes.PROTOBUF.toBytes(B_KEY));
            assertThat(actual).isEqualTo(INVALID_PATH);
        }

        @Test
        @DisplayName("kvPath lookup for unknown state")
        void kvPath_unknownState() {
            final long actual = virtualMapState.kvPath(rand.nextInt(65535), ProtoBytes.PROTOBUF.toBytes(A_KEY));
            assertThat(actual).isEqualTo(INVALID_PATH);
        }

        @Test
        @DisplayName("queueElementPath returns correct path for existing element and INVALID_PATH otherwise")
        void queueElementPath_foundAndNotFound() {
            final var firstIdxKey = StateUtils.getStateKeyForQueue(STEAM_STATE_ID, 1);
            // normally this shouldn't be happening as we don't delete values from VM directly.
            // This is needed to cover the case when VM returns null
            virtualMap.remove(firstIdxKey);

            final var thirdIdxKey = StateUtils.getStateKeyForQueue(STEAM_STATE_ID, 3);
            final var expectedPath = virtualMap.getRecords().findPath(thirdIdxKey);

            // Found case
            final long actualPathForBytes =
                    virtualMapState.queueElementPath(STEAM_STATE_ID, ProtoBytes.PROTOBUF.toBytes(CHEMISTRY));
            assertThat(actualPathForBytes).isEqualTo(expectedPath);

            final long actualPathForObj =
                    virtualMapState.queueElementPath(STEAM_STATE_ID, CHEMISTRY, ProtoBytes.PROTOBUF);
            assertThat(actualPathForObj).isEqualTo(expectedPath);
        }

        @Test
        @DisplayName("queueElementPath returns INVALID_PATH for a not found value")
        void queueElementPath_notFound() {
            final long actual =
                    virtualMapState.queueElementPath(STEAM_STATE_ID, ProtoBytes.PROTOBUF.toBytes(DISCIPLINE));
            assertThat(actual).isEqualTo(INVALID_PATH);
        }

        @Test
        @DisplayName("queueElementPath returns INVALID_PATH for unknown state")
        void queueElementPath_unknownState() {
            final int unknownStateId = rand.nextInt(65535);
            final long unknownStatePath =
                    virtualMapState.queueElementPath(unknownStateId, ProtoBytes.PROTOBUF.toBytes(CHEMISTRY));
            assertThat(unknownStatePath).isEqualTo(INVALID_PATH);
        }

        @Test
        @DisplayName("getHashByPath for existing path")
        void getHashByPath_existingPath() throws IOException {
            // trigger hash calculation for the state
            Hash rootHash = virtualMapState.getHash();

            /*
                                             Tree configuration:
                                                  root (0)
                                       /                               \
                               hash(1)                                 hash (2)
                             /         \                                /       \
                      hash(3)           hash(4)                 Apple (5)        Queue state (6)
                    /    \               /    \
            Ghana(7)   Biology(8)  Art(9)    Chemistry (10)
                  */
            assertThat(virtualMapState.getHashForPath(0)).isEqualTo(rootHash);
            for (int i = 1; i <= 10; i++) {
                assertNotNull(virtualMapState.getHashForPath(i));
            }
        }

        @Test
        @DisplayName("getHashByPath for non-existent path")
        void getHashByPath_nonExistentPath() {
            virtualMapState.getHash();
            assertNull(virtualMapState.getHashForPath(777));
        }
    }

    @AfterEach
    void tearDown() {
        if (virtualMapState.getRoot().getReservationCount() >= 0) {
            virtualMapState.release();
        }
        if (fruitVirtualMap != null && fruitVirtualMap.getReservationCount() >= 0) {
            fruitVirtualMap.release();
        }
        assertAllDatabasesClosed();
    }
}
