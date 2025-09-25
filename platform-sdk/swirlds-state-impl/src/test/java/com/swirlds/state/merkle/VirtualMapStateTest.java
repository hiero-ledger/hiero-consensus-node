// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertAllDatabasesClosed;
import static com.swirlds.platform.state.PlatformStateAccessor.GENESIS_ROUND;
import static com.swirlds.state.StateChangeListener.StateType.MAP;
import static com.swirlds.state.StateChangeListener.StateType.QUEUE;
import static com.swirlds.state.StateChangeListener.StateType.SINGLETON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.swirlds.base.state.MutabilityException;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.platform.test.fixtures.state.MerkleTestBase;
import com.swirlds.platform.test.fixtures.state.TestHederaVirtualMapState;
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
import com.swirlds.state.test.fixtures.StateTestBase;
import com.swirlds.state.test.fixtures.merkle.TestSchema;
import com.swirlds.virtualmap.VirtualMap;
import java.util.EnumSet;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.config.CryptoConfig;
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

    private TestHederaVirtualMapState virtualMapState;

    /**
     * Start with an empty Virtual Map State, but with the "fruit" map and metadata created and ready to
     * be added.
     */
    @BeforeEach
    void setUp() {
        virtualMapState = new TestHederaVirtualMapState(CONFIGURATION, new NoOpMetrics());
        virtualMapState.init(
                new FakeTime(),
                CONFIGURATION,
                new NoOpMetrics(),
                mock(MerkleCryptography.class),
                () -> PlatformStateAccessor.GENESIS_ROUND);
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
                    new TestSchema(1),
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

        @Test
        @DisplayName("Checking the content of getInfoJson")
        void testGetInfoJson() {
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

            // hash the state
            virtualMapState.getHash();

            // Then we can check the content of getInfoJson
            final String infoJson = virtualMapState.getInfoJson();
            assertThat(infoJson)
                    .isEqualTo("{" + "\"Queues (Queue States)\":"
                            + "{\"First-Service." + STEAM_STATE_KEY + "\":{\"head\":1,\"path\":8,\"tail\":3}},"
                            + "\"VirtualMapMetadata\":{\"firstLeafPath\":5,\"lastLeafPath\":10},"
                            + "\"Singletons\":"
                            + "{\"First-Service." + COUNTRY_STATE_KEY
                            + "\":{\"path\":9,\"mnemonic\":\"cushion-bright-early-flight\"}}}");
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

            final MerkleCryptography merkleCryptography = MerkleCryptographyFactory.create(ConfigurationBuilder.create()
                    .withConfigDataType(CryptoConfig.class)
                    .build());
            virtualMapState.init(
                    new FakeTime(), CONFIGURATION, new NoOpMetrics(), merkleCryptography, () -> GENESIS_ROUND);
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
