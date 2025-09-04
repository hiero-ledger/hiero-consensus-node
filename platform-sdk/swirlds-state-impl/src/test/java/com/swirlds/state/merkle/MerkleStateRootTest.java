// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.platform.state.PlatformStateAccessor.GENESIS_ROUND;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.registerMerkleStateRootClassIds;
import static com.swirlds.state.StateChangeListener.StateType.MAP;
import static com.swirlds.state.StateChangeListener.StateType.QUEUE;
import static com.swirlds.state.StateChangeListener.StateType.SINGLETON;
import static com.swirlds.state.merkle.MerkleStateRoot.CURRENT_VERSION;
import static com.swirlds.state.merkle.MerkleStateRoot.MINIMUM_SUPPORTED_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.swirlds.base.state.MutabilityException;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.test.fixtures.state.MerkleTestBase;
import com.swirlds.platform.test.fixtures.state.TestMerkleStateRoot;
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
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @deprecated This test class is only required for the testing of MerkleStateRoot class and will be removed together with that class.
 */
@ExtendWith(MockitoExtension.class)
@Deprecated
class MerkleStateRootTest extends MerkleTestBase {

    /** The merkle tree we will test with */
    private TestMerkleStateRoot stateRoot;

    /**
     * Start with an empty Merkle Tree, but with the "fruit" map and metadata created and ready to
     * be added.
     */
    @BeforeEach
    void setUp() {
        setupConstructableRegistry();
        registerMerkleStateRootClassIds();
        stateRoot = new TestMerkleStateRoot();
        stateRoot.init(
                new FakeTime(), CONFIGURATION, new NoOpMetrics(), mock(MerkleCryptography.class), () -> GENESIS_ROUND);
    }

    /** Looks for a merkle node with the given label */
    MerkleNode getNodeForLabel(String label) {
        return getNodeForLabel(stateRoot, label);
    }

    @Nested
    @DisplayName("Service Registration Tests")
    final class RegistrationTest {

        @BeforeEach
        void setUp() {
            setupFruitVirtualMap();
        }

        @AfterEach
        void tearDown() {
            fruitVirtualMap.release();
            MerkleDbTestUtils.assertAllDatabasesClosed();
        }

        @Test
        @DisplayName("Adding a null service metadata will throw an NPE")
        void addingNullServiceMetaDataThrows() {
            //noinspection ConstantConditions
            assertThatThrownBy(() -> stateRoot.putServiceStateIfAbsent(null, () -> fruitVirtualMap))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Adding a null service node will throw an NPE")
        void addingNullServiceNodeThrows() {
            //noinspection ConstantConditions
            assertThatThrownBy(() -> stateRoot.putServiceStateIfAbsent(fruitMetadata, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Adding a service node that is not Labeled throws IAE")
        void addingWrongKindOfNodeThrows() {
            assertThatThrownBy(() ->
                            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> Mockito.mock(MerkleNode.class)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Adding a service node without a label throws IAE")
        void addingNodeWithNoLabelThrows() {
            final var fruitNodeNoLabel = Mockito.mock(MerkleMap.class);
            Mockito.when(fruitNodeNoLabel.getLabel()).thenReturn(null);
            assertThatThrownBy(() -> stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitNodeNoLabel))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Adding a service node with a label that doesn't match service name and state key throws IAE")
        void addingBadServiceNodeNameThrows() {
            fruitVirtualMap.getMetadata().setLabel("Some Random Label");
            assertThatThrownBy(() -> stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Adding a service")
        void addingService() {
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);
            assertThat(stateRoot.getNumberOfChildren()).isEqualTo(1);
            assertThat(getNodeForLabel(fruitVirtualLabel)).isSameAs(fruitVirtualMap);
        }

        @Test
        @DisplayName("Adding a service with VirtualMap")
        void addingVirtualMapService() {
            // Given a virtual map

            // When added to the merkle tree
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);

            // Then we can see it is on the tree
            assertThat(stateRoot.getNumberOfChildren()).isEqualTo(1);
            assertThat(getNodeForLabel(fruitVirtualLabel)).isSameAs(fruitVirtualMap);
        }

        @Test
        @DisplayName("Adding a service with a Singleton node")
        void addingSingletonService() {
            // Given a singleton node
            setupSingletonCountry();

            // When added to the merkle tree
            stateRoot.putServiceStateIfAbsent(countryMetadata, () -> countrySingleton);

            // Then we can see it is on the tree
            assertThat(stateRoot.getNumberOfChildren()).isEqualTo(1);
            assertThat(getNodeForLabel(countryLabel)).isSameAs(countrySingleton);
        }

        @Test
        @DisplayName("Adding a service with a Queue node")
        void addingQueueService() {
            // Given a queue node
            setupSteamQueue();

            // When added to the merkle tree
            stateRoot.putServiceStateIfAbsent(steamMetadata, () -> steamQueue);

            // Then we can see it is on the tree
            assertThat(stateRoot.getNumberOfChildren()).isEqualTo(1);
            assertThat(getNodeForLabel(steamLabel)).isSameAs(steamQueue);
        }

        @Test
        @DisplayName("Adding a service to a MerkleStateRoot that has other node types on it")
        void addingServiceWhenNonServiceNodeChildrenExist() {
            stateRoot.setChild(0, Mockito.mock(MerkleNode.class));
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);
            assertThat(stateRoot.getNumberOfChildren()).isEqualTo(2);
            assertThat(getNodeForLabel(fruitVirtualLabel)).isSameAs(fruitVirtualMap);
        }

        @Test
        @DisplayName("Adding the same service twice is idempotent")
        void addingServiceTwiceIsIdempotent() {
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);
            assertThat(stateRoot.getNumberOfChildren()).isEqualTo(1);
            assertThat(getNodeForLabel(fruitVirtualLabel)).isSameAs(fruitVirtualMap);
        }

        @Test
        @DisplayName("Adding the same service node twice with two different metadata replaces the metadata")
        void addingServiceTwiceWithDifferentMetadata() {
            // Given an empty merkle tree, when I add the same node twice but with different
            // metadata,
            final var fruitMetadata2 = new StateMetadata<>(
                    FIRST_SERVICE,
                    new TestSchema(1),
                    StateDefinition.inMemory(
                            FRUIT_STATE_ID, FRUIT_STATE_KEY, ProtoBytes.PROTOBUF, ProtoBytes.PROTOBUF));

            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);
            stateRoot.putServiceStateIfAbsent(fruitMetadata2, () -> fruitVirtualMap);

            // Then the original node is kept and the second node ignored
            assertThat(stateRoot.getNumberOfChildren()).isEqualTo(1);
            assertThat(getNodeForLabel(fruitVirtualLabel)).isSameAs(fruitVirtualMap);

            // NOTE: I don't have a good way to test that the metadata is intact...
        }
    }

    @Nested
    @DisplayName("ReadableStates Tests")
    final class ReadableStatesTest {

        @BeforeEach
        void setUp() {
            setupFruitVirtualMap();
            setupSingletonCountry();
            setupSteamQueue();

            addKvState(fruitVirtualMap, fruitMetadata, A_KEY, APPLE);
            addKvState(fruitVirtualMap, fruitMetadata, B_KEY, BANANA);
            countrySingleton.setValue(GHANA);
            steamQueue.add(ART);
        }

        @AfterEach
        void tearDown() {
            fruitVirtualMap.release();
            MerkleDbTestUtils.assertAllDatabasesClosed();
        }

        @Test
        @DisplayName("Getting ReadableStates on an unknown service returns an empty entity")
        void unknownServiceNameUsingReadableStates() {
            final var states = stateRoot.getReadableStates(UNKNOWN_SERVICE);
            assertThat(states).isNotNull();
            assertThat(states.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("Reading an unknown state on ReadableStates should throw IAE")
        void unknownState() {
            // Given a State with the fruit and animal and country states
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);
            stateRoot.putServiceStateIfAbsent(countryMetadata, () -> countrySingleton);
            stateRoot.putServiceStateIfAbsent(steamMetadata, () -> steamQueue);

            // When we get the ReadableStates
            final var states = stateRoot.getReadableStates(FIRST_SERVICE);

            // Then query it for an unknown state and get an IAE
            assertThatThrownBy(() -> states.get(UNKNOWN_STATE_ID)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Read a virtual map")
        void readVirtualMap() {
            // Given a State with the fruit virtual map
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);

            // When we get the ReadableStates
            final var states = stateRoot.getReadableStates(FIRST_SERVICE);

            // Then it isn't null
            assertThat(states.get(FRUIT_STATE_ID)).isNotNull();
        }

        @Test
        @DisplayName("Contains is true for all states in stateKeys and false for unknown ones")
        void contains() {
            // Given a State with the fruit and animal and country and steam states
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);
            stateRoot.putServiceStateIfAbsent(countryMetadata, () -> countrySingleton);
            stateRoot.putServiceStateIfAbsent(steamMetadata, () -> steamQueue);

            // When we get the ReadableStates and the state keys
            final var states = stateRoot.getReadableStates(FIRST_SERVICE);
            final var stateIds = states.stateIds();

            // Then we find "contains" is true for every state in stateKeys
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
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);
            final var states = stateRoot.getReadableStates(FIRST_SERVICE);

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
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);
            stateRoot.putServiceStateIfAbsent(countryMetadata, () -> countrySingleton);
            stateRoot.putServiceStateIfAbsent(steamMetadata, () -> steamQueue);

            // When we get the ReadableStates
            final var states = stateRoot.getReadableStates(FIRST_SERVICE);

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
            setupFruitVirtualMap();
            setupSingletonCountry();
            setupSteamQueue();

            addKvState(fruitVirtualMap, fruitMetadata, A_KEY, APPLE);
            addKvState(fruitVirtualMap, fruitMetadata, B_KEY, BANANA);
            countrySingleton.setValue(FRANCE);
            steamQueue.add(ART);
        }

        @AfterEach
        void tearDown() {
            fruitVirtualMap.release();
            MerkleDbTestUtils.assertAllDatabasesClosed();
        }

        @Test
        @DisplayName("Getting WritableStates on an unknown service returns an empty entity")
        void unknownServiceNameUsingWritableStates() {
            final var states = stateRoot.getWritableStates(UNKNOWN_SERVICE);
            assertThat(states).isNotNull();
            assertThat(states.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("Reading an unknown state on WritableState should throw IAE")
        void unknownState() {
            // Given a State with the fruit and animal and country states
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);
            stateRoot.putServiceStateIfAbsent(countryMetadata, () -> countrySingleton);
            stateRoot.putServiceStateIfAbsent(steamMetadata, () -> steamQueue);

            // When we get the WritableStates
            final var states = stateRoot.getWritableStates(FIRST_SERVICE);

            // Then query it for an unknown state and get an IAE
            assertThatThrownBy(() -> states.get(UNKNOWN_STATE_ID)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Read a virtual map")
        void readVirtualMap() {
            // Given a State with the fruit virtual map
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);

            // When we get the WritableStates
            final var states = stateRoot.getWritableStates(FIRST_SERVICE);

            // Then it isn't null
            assertThat(states.get(FRUIT_STATE_ID)).isNotNull();
        }

        @Test
        @DisplayName("Contains is true for all states in stateKeys and false for unknown ones")
        void contains() {
            // Given a State with the fruit and animal and country states
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);
            stateRoot.putServiceStateIfAbsent(countryMetadata, () -> countrySingleton);
            stateRoot.putServiceStateIfAbsent(steamMetadata, () -> steamQueue);

            // When we get the WritableStates and the state keys
            final var states = stateRoot.getWritableStates(FIRST_SERVICE);
            final var stateIds = states.stateIds();

            // Then we find "contains" is true for every state in stateKeys
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
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);
            final var states = stateRoot.getWritableStates(FIRST_SERVICE);

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
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);
            stateRoot.putServiceStateIfAbsent(countryMetadata, () -> countrySingleton);
            stateRoot.putServiceStateIfAbsent(steamMetadata, () -> steamQueue);

            // When we get the WritableStates
            final var states = stateRoot.getWritableStates(FIRST_SERVICE);

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
            stateRoot.copy();
            assertThatThrownBy(stateRoot::copy).isInstanceOf(MutabilityException.class);
        }

        @Test
        @DisplayName("Cannot call putServiceStateIfAbsent on original after copy")
        void addServiceOnOriginalAfterCopyThrows() {
            setupFruitVirtualMap();
            try {
                stateRoot.copy();
                assertThatThrownBy(() -> stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap))
                        .isInstanceOf(MutabilityException.class);
            } finally {
                fruitVirtualMap.release();
            }
        }

        @Test
        @DisplayName("Cannot call createWritableStates on original after copy")
        void createWritableStatesOnOriginalAfterCopyThrows() {
            stateRoot.copy();
            assertThatThrownBy(() -> stateRoot.getWritableStates(FRUIT_STATE_KEY))
                    .isInstanceOf(MutabilityException.class);
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

            setupFruitVirtualMap();
            setupSingletonCountry();
            setupSteamQueue();

            addKvState(fruitVirtualMap, fruitMetadata, C_KEY, CHERRY);
            countrySingleton.setValue(FRANCE);
            steamQueue.add(ART);
        }

        @Test
        void appropriateListenersAreInvokedOnCommit() {
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);
            stateRoot.putServiceStateIfAbsent(countryMetadata, () -> countrySingleton);
            stateRoot.putServiceStateIfAbsent(steamMetadata, () -> steamQueue);

            stateRoot.registerCommitListener(kvListener);
            stateRoot.registerCommitListener(singletonListener);
            stateRoot.registerCommitListener(queueListener);

            final var states = stateRoot.getWritableStates(FIRST_SERVICE);
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
            MerkleDbTestUtils.assertAllDatabasesClosed();
        }
    }

    @Nested
    @DisplayName("Migrate test")
    class MigrateTest {
        @Test
        @DisplayName("No MerkleStateRoot migration is expected at this point")
        void migrateFromMinimumSupportedVersion() {
            var node1 = mock(MerkleNode.class);
            stateRoot.setChild(0, node1);
            var node2 = mock(MerkleNode.class);
            stateRoot.setChild(1, node2);
            reset(node1, node2);
            var migratedState = stateRoot.migrate(CONFIGURATION, MINIMUM_SUPPORTED_VERSION);
            assertSame(stateRoot, migratedState);
            verifyNoMoreInteractions(node1, node2);
            migratedState.release();
        }

        @Test
        @DisplayName("Migration from previous versions is supported")
        void migrationSupported() {
            assertDoesNotThrow(
                    () -> stateRoot.migrate(CONFIGURATION, CURRENT_VERSION - 1).release());
        }
    }

    @Nested
    @DisplayName("Hashing test")
    class HashingTest {
        private MerkleCryptography merkleCryptography;

        @BeforeEach
        void setUp() {
            setupSingletonCountry();
            setupSteamQueue();
            setupFruitVirtualMap();

            addKvState(fruitVirtualMap, fruitMetadata, A_KEY, APPLE);
            addKvState(fruitVirtualMap, fruitMetadata, B_KEY, BANANA);
            countrySingleton.setValue(GHANA);
            steamQueue.add(ART);

            // Given a State with the fruit and animal and country states
            stateRoot.putServiceStateIfAbsent(fruitMetadata, () -> fruitVirtualMap);
            stateRoot.putServiceStateIfAbsent(countryMetadata, () -> countrySingleton);
            stateRoot.putServiceStateIfAbsent(steamMetadata, () -> steamQueue);

            merkleCryptography = MerkleCryptographyFactory.create(ConfigurationBuilder.create()
                    .withConfigDataType(CryptoConfig.class)
                    .build());
            stateRoot.init(new FakeTime(), CONFIGURATION, new NoOpMetrics(), merkleCryptography, () -> GENESIS_ROUND);
        }

        @AfterEach
        void tearDown() {
            fruitVirtualMap.release();
            MerkleDbTestUtils.assertAllDatabasesClosed();
        }

        @Test
        @DisplayName("No hash by default")
        void noHashByDefault() {
            assertNull(stateRoot.getHash());
        }

        @Test
        @DisplayName("computeHash is doesn't work on mutable states")
        void calculateHashOnMutable() {
            assertThrows(IllegalStateException.class, stateRoot::computeHash);
        }

        @Test
        @DisplayName("computeHash is doesn't work on destroyed states")
        void calculateHashOnDestroyed() {
            stateRoot.destroyNode();
            assertThrows(IllegalStateException.class, stateRoot::computeHash);
        }

        @Test
        @DisplayName("Hash is computed after computeHash invocation")
        void calculateHash() {
            final TestMerkleStateRoot stateRootCopy = stateRoot.copy();
            try {
                stateRoot.computeHash();
                assertNotNull(stateRoot.getHash());
            } finally {
                stateRootCopy.release();
            }
        }

        @Test
        @DisplayName("computeHash is idempotent")
        void calculateHash_idempotent() {
            final TestMerkleStateRoot stateRootCopy = stateRoot.copy();
            try {
                stateRoot.computeHash();
                Hash hash1 = stateRoot.getHash();
                stateRoot.computeHash();
                Hash hash2 = stateRoot.getHash();
                assertSame(hash1, hash2);
            } finally {
                stateRootCopy.release();
            }
        }
    }

    protected void addKvState(VirtualMap map, String serviceName, int stateId, ProtoBytes key, ProtoBytes value) {
        map.put(ProtoBytes.PROTOBUF.toBytes(key), value, ProtoBytes.PROTOBUF);
    }
}
