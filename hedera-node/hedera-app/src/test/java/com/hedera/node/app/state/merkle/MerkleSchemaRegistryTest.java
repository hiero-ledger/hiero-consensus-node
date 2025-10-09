// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle;

import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.services.MigrationStateChanges;
import com.hedera.node.app.spi.fixtures.TestSchema;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.config.data.HederaConfig;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.test.fixtures.state.MerkleTestBase;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.test.fixtures.merkle.TestVirtualMapState;
import com.swirlds.state.test.fixtures.merkle.VirtualMapUtils;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.base.constructable.ConstructableRegistry;
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
 * Tests for the {@link MerkleSchemaRegistry}. The only thing not covered here are serialization
 * tests, they are covered in {@link SerializationTest}.
 */
@ExtendWith(MockitoExtension.class)
class MerkleSchemaRegistryTest extends MerkleTestBase {

    @Mock
    private MigrationStateChanges migrationStateChanges;

    @Mock
    private StartupNetworks startupNetworks;

    private MerkleSchemaRegistry schemaRegistry;
    private Configuration config;

    @BeforeEach
    void setUp() {
        // We don't need a real registry, and the unit tests are much
        // faster if we use a mocked one
        registry = mock(ConstructableRegistry.class);
        schemaRegistry = new MerkleSchemaRegistry(FIRST_SERVICE, new SchemaApplications());
        config = mock(Configuration.class);
        final var hederaConfig = mock(HederaConfig.class);
        lenient().when(config.getConfigData(HederaConfig.class)).thenReturn(hederaConfig);
        final var merkleDbConfig = mock(MerkleDbConfig.class);
        lenient().when(merkleDbConfig.goodAverageBucketEntryCount()).thenReturn(32);
        lenient().when(merkleDbConfig.longListChunkSize()).thenReturn(1024);
        lenient().when(merkleDbConfig.maxNumOfKeys()).thenReturn(1000L);
        lenient().when(config.getConfigData(MerkleDbConfig.class)).thenReturn(merkleDbConfig);
        final var virtualMapConfig = mock(VirtualMapConfig.class);
        lenient().when(config.getConfigData(VirtualMapConfig.class)).thenReturn(virtualMapConfig);
        final var temporaryFileDbConfig = mock(TemporaryFileConfig.class);
        lenient().when(config.getConfigData(TemporaryFileConfig.class)).thenReturn(temporaryFileDbConfig);
        final var stateCommonConfig = mock(StateCommonConfig.class);
        lenient().when(config.getConfigData(StateCommonConfig.class)).thenReturn(stateCommonConfig);
        lenient()
                .when(temporaryFileDbConfig.getTemporaryFilePath(stateCommonConfig))
                .thenReturn("test");
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTest {

        @Test
        @DisplayName("A null serviceName throws")
        void nullServiceNameThrows() {
            //noinspection ConstantConditions
            assertThatThrownBy(() -> new MerkleSchemaRegistry(null, new SchemaApplications()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("A null schemaUseAnalysis throws")
        void nullSchemaUseAnalysisBuilderThrows() {
            //noinspection ConstantConditions
            assertThatThrownBy(() -> new MerkleSchemaRegistry(FIRST_SERVICE, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Registration Tests")
    class RegistrationTest {
        @Test
        @DisplayName("Registering with a null Schema throws NPE")
        void nullSchemaThrows() {
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> schemaRegistry.register(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Registering with a schema")
        void registerOnce() {
            // Given a schema
            final var schema = Mockito.spy(new TestSchema(10));

            // When it is registered
            schemaRegistry.register(schema);

            // Then on migrateFromV9ToV10, it is called
            migrateFromV9ToV10();
            Mockito.verify(schema, Mockito.times(1)).migrate(Mockito.any());
        }

        @Test
        @DisplayName("Registering with the same schema twice")
        void registerTwice() {
            // Given a schema
            final var schema = Mockito.spy(new TestSchema(10));

            // When it is registered twice
            schemaRegistry.register(schema);
            schemaRegistry.register(schema);

            // Then on migrateFromV9ToV10, it is called only once
            migrateFromV9ToV10();
            Mockito.verify(schema, Mockito.times(1)).migrate(Mockito.any());
        }

        @Test
        @DisplayName("Registering two schemas that are different but have the same version number, the second is used")
        void registerSameVersionDifferentInstances() {
            // Given two schemas which do different things but have the same version
            final var schema1 = Mockito.spy(new TestSchema(10));
            final var schema2 = Mockito.spy(new TestSchema(10));

            // When they are both registered
            schemaRegistry.register(schema1);
            schemaRegistry.register(schema2);

            // Then on migrateFromV9ToV10, the last one registered wins
            migrateFromV9ToV10();
            Mockito.verify(schema1, Mockito.times(0)).migrate(Mockito.any());
            Mockito.verify(schema2, Mockito.times(1)).migrate(Mockito.any());
        }

        /**
         * Utility method that migrates from version 9 to 10
         */
        void migrateFromV9ToV10() {
            final var virtualMapLabel =
                    "vm-" + MerkleSchemaRegistryTest.class.getSimpleName() + "-" + java.util.UUID.randomUUID();
            final var virtualMap = VirtualMapUtils.createVirtualMap(virtualMapLabel);
            SemanticVersion latestVersion = version(10, 0, 0);
            schemaRegistry.migrate(
                    new TestVirtualMapState(virtualMap),
                    version(9, 0, 0),
                    latestVersion,
                    config,
                    config,
                    new HashMap<>(),
                    migrationStateChanges,
                    startupNetworks,
                    TEST_PLATFORM_STATE_FACADE);
            virtualMap.release();
        }
    }

    @Nested
    @DisplayName("Migration Tests")
    class MigrationTest {
        private MerkleNodeState merkleTree;
        private SemanticVersion[] versions;

        @BeforeEach
        void setUp() {

            // Let the first version[0] be null, and all others have a number
            versions = new SemanticVersion[10];
            for (int i = 1; i < versions.length; i++) {
                versions[i] = version(0, i, 0);
            }
            final var virtualMapLabel =
                    "vm-" + MerkleSchemaRegistryTest.class.getSimpleName() + "-" + java.util.UUID.randomUUID();
            merkleTree = TestVirtualMapState.createInstanceWithVirtualMapLabel(virtualMapLabel);
        }

        @AfterEach
        void tearDown() {
            merkleTree.release();
            if (fruitVirtualMap != null && fruitVirtualMap.getReservationCount() >= 0) {
                fruitVirtualMap.release();
            }
        }

        @Test
        @DisplayName("Calling migrate with a null hederaState throws NPE")
        void nullMerkleThrows() {
            //noinspection ConstantConditions
            assertThatThrownBy(() -> schemaRegistry.migrate(
                            null,
                            versions[0],
                            versions[1],
                            config,
                            config,
                            new HashMap<>(),
                            migrationStateChanges,
                            startupNetworks,
                            TEST_PLATFORM_STATE_FACADE))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Calling migrate with a null currentVersion throws NPE")
        void nullCurrentVersionThrows() {
            //noinspection ConstantConditions
            assertThatThrownBy(() -> schemaRegistry.migrate(
                            merkleTree,
                            versions[0],
                            null,
                            config,
                            config,
                            new HashMap<>(),
                            migrationStateChanges,
                            startupNetworks,
                            TEST_PLATFORM_STATE_FACADE))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Calling migrate with a null node config throws NPE")
        void nullNodeConfigVersionThrows() {
            //noinspection ConstantConditions
            assertThatThrownBy(() -> schemaRegistry.migrate(
                            merkleTree,
                            versions[0],
                            versions[1],
                            null,
                            null,
                            new HashMap<>(),
                            migrationStateChanges,
                            startupNetworks,
                            TEST_PLATFORM_STATE_FACADE))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Calling migrate with a null node config throws NPE")
        void nullNodeConfigVersionThrows1() {
            //noinspection ConstantConditions
            assertThatThrownBy(() -> schemaRegistry.migrate(
                            merkleTree,
                            versions[0],
                            versions[1],
                            null,
                            config,
                            new HashMap<>(),
                            migrationStateChanges,
                            startupNetworks,
                            TEST_PLATFORM_STATE_FACADE))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Calling migrate with a currentVersion < previousVersion throws IAE")
        void currentVersionLessThanPreviousVersionThrows() {
            //noinspection ConstantConditions
            assertThatThrownBy(() -> schemaRegistry.migrate(
                            merkleTree,
                            versions[5],
                            versions[4],
                            config,
                            config,
                            new HashMap<>(),
                            migrationStateChanges,
                            startupNetworks,
                            TEST_PLATFORM_STATE_FACADE))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Migration is skipped if previousVersion == currentVersion")
        void migrateIsSkippedIfVersionsAreTheSame() {
            // Given a schema
            final var schema = Mockito.spy(new TestSchema(versions[1]));

            // When it is registered twice and migrate is called
            schemaRegistry.register(schema);
            schemaRegistry.migrate(
                    merkleTree,
                    versions[1],
                    versions[1],
                    config,
                    config,
                    new HashMap<>(),
                    migrationStateChanges,
                    startupNetworks,
                    TEST_PLATFORM_STATE_FACADE);

            // Then nothing happens
            Mockito.verify(schema, Mockito.times(0)).migrate(Mockito.any());
        }

        @Test
        @DisplayName("Considered as Restart of schema version is before current software version")
        void considersAsRestartIfSchemaVersionIsBeforeCurrentVersion() {
            // Given a schema
            final var schema = Mockito.spy(new TestSchema(versions[1]));

            // When it is registered twice and migrate is called
            schemaRegistry.register(schema);
            schemaRegistry.migrate(
                    merkleTree,
                    versions[1],
                    versions[5],
                    config,
                    config,
                    new HashMap<>(),
                    migrationStateChanges,
                    startupNetworks,
                    TEST_PLATFORM_STATE_FACADE);

            // Then migration doesn't happen but restart is called
            Mockito.verify(schema, Mockito.times(0)).migrate(Mockito.any());
            Mockito.verify(schema, Mockito.times(1)).restart(Mockito.any());
        }

        @Test
        @DisplayName("Considered as Migration if previous version is null")
        void considersAsMigrationIfPreviousVersionIsNull() {
            // Given a schema
            final var schema = Mockito.spy(new TestSchema(versions[1]));

            // When it is registered twice and migrate is called
            schemaRegistry.register(schema);
            schemaRegistry.migrate(
                    merkleTree,
                    null,
                    versions[5],
                    config,
                    config,
                    new HashMap<>(),
                    migrationStateChanges,
                    startupNetworks,
                    TEST_PLATFORM_STATE_FACADE);

            // Then migration doesn't happen but restart is called
            Mockito.verify(schema, Mockito.times(1)).migrate(Mockito.any());
            Mockito.verify(schema, Mockito.times(1)).restart(Mockito.any());
        }

        @Test
        @DisplayName("Migration captures all appropriate schemas even when they skip versions")
        void migrateWhenSchemasSkipVersions() {
            // We will place into this list each schema as it is called
            final var called = new LinkedList<SemanticVersion>();

            // Given a schema for v1, v4, v6
            final var schemaV1 = new TestSchema(versions[1], () -> called.add(versions[1]));
            final var schemaV4 = new TestSchema(versions[4], () -> called.add(versions[4]));
            final var schemaV6 = new TestSchema(versions[6], () -> called.add(versions[6]));

            schemaRegistry.register(schemaV1);
            schemaRegistry.register(schemaV4);
            schemaRegistry.register(schemaV6);

            // When we migrate from v0 to v7
            schemaRegistry.migrate(
                    merkleTree,
                    null,
                    versions[7],
                    config,
                    config,
                    new HashMap<>(),
                    migrationStateChanges,
                    startupNetworks,
                    TEST_PLATFORM_STATE_FACADE);

            // Then each of v1, v4, and v6 are called
            assertThat(called).hasSize(3);
            assertThat(called.removeFirst()).isSameAs(versions[1]);
            assertThat(called.removeFirst()).isSameAs(versions[4]);
            assertThat(called.removeFirst()).isSameAs(versions[6]);
        }

        /**
         * In these tests, each migration will apply some kind of state change to the tree.
         */
        @Nested
        @DisplayName("Migration State Impact Tests")
        class StateImpactTest {
            Schema createV1Schema() {
                return new TestSchema(versions[1]) {
                    @NonNull
                    @Override
                    @SuppressWarnings("rawtypes")
                    public Set<StateDefinition> statesToCreate() {
                        final var fruitDef = StateDefinition.inMemory(
                                FRUIT_STATE_ID, FRUIT_STATE_KEY, ProtoBytes.PROTOBUF, ProtoBytes.PROTOBUF);
                        return Set.of(fruitDef);
                    }

                    @Override
                    public void migrate(@NonNull final MigrationContext ctx) {
                        assertThat(ctx).isNotNull();
                        assertThat(ctx.previousVersion()).isNull();
                        assertThat(ctx.newStates().size()).isEqualTo(1);
                        final WritableKVState<ProtoBytes, ProtoBytes> fruit =
                                ctx.newStates().get(FRUIT_STATE_ID);
                        fruit.put(A_KEY, APPLE);
                        fruit.put(B_KEY, BANANA);
                        fruit.put(C_KEY, CHERRY);
                    }
                };
            }

            Schema createV2Schema() {
                return new TestSchema(versions[2]) {
                    @NonNull
                    @Override
                    @SuppressWarnings("rawtypes")
                    public Set<StateDefinition> statesToCreate() {
                        final var learningDef = StateDefinition.onDisk(
                                STEAM_STATE_ID, STEAM_STATE_KEY, ProtoBytes.PROTOBUF, ProtoBytes.PROTOBUF, 100);
                        final var countryDef =
                                StateDefinition.singleton(COUNTRY_STATE_ID, COUNTRY_STATE_KEY, ProtoBytes.PROTOBUF);
                        return Set.of(learningDef, countryDef);
                    }

                    @Override
                    public void migrate(@NonNull final MigrationContext ctx) {
                        assertThat(ctx).isNotNull();
                        final var previousStates = ctx.previousStates();
                        final var newStates = ctx.newStates();

                        // First check that the previous states only includes what was there before,
                        // and nothing new
                        assertThat(previousStates.isEmpty()).isFalse();
                        assertThat(previousStates.contains(FRUIT_STATE_ID)).isTrue();
                        final ReadableKVState<ProtoBytes, ProtoBytes> oldFruit = previousStates.get(FRUIT_STATE_ID);
                        assertThat(oldFruit.keys()).toIterable().hasSize(3);
                        assertThat(oldFruit.get(A_KEY)).isEqualTo(APPLE);
                        assertThat(oldFruit.get(B_KEY)).isEqualTo(BANANA);
                        assertThat(oldFruit.get(C_KEY)).isEqualTo(CHERRY);

                        // Now check that the new states contains the new states
                        assertThat(newStates.size()).isEqualTo(3);
                        assertThat(newStates.contains(FRUIT_STATE_ID)).isTrue();
                        assertThat(newStates.contains(STEAM_STATE_ID)).isTrue();
                        assertThat(newStates.contains(COUNTRY_STATE_ID)).isTrue();

                        // Add in the new learning
                        final WritableKVState<ProtoBytes, ProtoBytes> learning = newStates.get(STEAM_STATE_ID);
                        learning.put(A_KEY, ART);
                        learning.put(B_KEY, BIOLOGY);

                        // Remove, update, and add fruit
                        final WritableKVState<ProtoBytes, ProtoBytes> fruit = newStates.get(FRUIT_STATE_ID);
                        fruit.remove(A_KEY);
                        fruit.put(B_KEY, BLACKBERRY);
                        fruit.put(E_KEY, EGGPLANT);

                        // Initialize the COUNTRY to be BRAZIL
                        final WritableSingletonState<ProtoBytes> country = newStates.getSingleton(COUNTRY_STATE_ID);
                        country.put(BRAZIL);

                        // And the old states shouldn't have a COUNTRY_STATE_KEY
                        assertThat(previousStates.contains(COUNTRY_STATE_ID)).isFalse();

                        // Make sure old fruit hasn't been changed in any way
                        assertThat(oldFruit.keys()).toIterable().hasSize(3);
                        assertThat(oldFruit.get(A_KEY)).isEqualTo(APPLE);
                        assertThat(oldFruit.get(B_KEY)).isEqualTo(BANANA);
                        assertThat(oldFruit.get(C_KEY)).isEqualTo(CHERRY);
                    }
                };
            }

            Schema createV3Schema() {
                return new TestSchema(versions[3]) {
                    @NonNull
                    @Override
                    public Set<Integer> statesToRemove() {
                        return Set.of(FRUIT_STATE_ID, COUNTRY_STATE_ID);
                    }

                    @Override
                    public void migrate(@NonNull MigrationContext ctx) {
                        assertThat(ctx).isNotNull();
                        final var previousStates = ctx.previousStates();
                        final var newStates = ctx.newStates();

                        // Verify that everything in v2 is still here
                        assertThat(previousStates.stateIds())
                                .containsExactlyInAnyOrder(FRUIT_STATE_ID, STEAM_STATE_ID, COUNTRY_STATE_ID);
                        final ReadableKVState<ProtoBytes, ProtoBytes> oldFruit = previousStates.get(FRUIT_STATE_ID);
                        assertThat(oldFruit.keys()).toIterable().containsExactlyInAnyOrder(B_KEY, C_KEY, E_KEY);
                        assertThat(oldFruit.get(B_KEY)).isEqualTo(BLACKBERRY);
                        assertThat(oldFruit.get(C_KEY)).isEqualTo(CHERRY);
                        assertThat(oldFruit.get(E_KEY)).isEqualTo(EGGPLANT);
                        final ReadableKVState<ProtoBytes, ProtoBytes> oldLearning = previousStates.get(STEAM_STATE_ID);
                        assertThat(oldLearning.get(A_KEY)).isEqualTo(ART);
                        assertThat(oldLearning.get(B_KEY)).isEqualTo(BIOLOGY);

                        // Now check that the new states contains both states as well (since I am
                        // not adding any)
                        assertThat(newStates.size()).isEqualTo(1);
                        assertThat(newStates.contains(STEAM_STATE_ID)).isTrue();

                        // Add in a new learning
                        final WritableKVState<ProtoBytes, ProtoBytes> learning = newStates.get(STEAM_STATE_ID);
                        learning.put(C_KEY, CHEMISTRY);

                        // And I should still see the COUNTRY_STATE_KEY in the previousStates,
                        // but not in the newStates
                        final ReadableSingletonState<ProtoBytes> country =
                                previousStates.getSingleton(COUNTRY_STATE_ID);
                        assertThat(country.get()).isEqualTo(BRAZIL);
                        assertThat(newStates.contains(COUNTRY_STATE_ID)).isFalse();

                        // The newStates should not see the fruit map
                        assertThatThrownBy(() -> newStates.get(FRUIT_STATE_ID))
                                .isInstanceOf(IllegalArgumentException.class);
                    }
                };
            }

            @Test
            @DisplayName("Migration from genesis sees nothing in oldStates but can insert into new" + " states")
            void genesis() {
                // Given a schema that adds the FRUIT state with k/v for A, B, and C
                final var schemaV1 = createV1Schema();

                // When we migrate
                schemaRegistry.register(schemaV1);
                schemaRegistry.migrate(
                        merkleTree,
                        versions[0],
                        versions[1],
                        config,
                        config,
                        new HashMap<>(),
                        migrationStateChanges,
                        startupNetworks,
                        TEST_PLATFORM_STATE_FACADE);

                // Then we see that the values for A, B, and C are available
                final var readableStates = merkleTree.getReadableStates(FIRST_SERVICE);
                assertThat(readableStates.size()).isEqualTo(1);
                final ReadableKVState<ProtoBytes, ProtoBytes> fruitV1 = readableStates.get(FRUIT_STATE_ID);
                assertThat(fruitV1.keys()).toIterable().containsExactlyInAnyOrder(A_KEY, B_KEY, C_KEY);
            }

            @Test
            @DisplayName("Migration from a former version and add a new state")
            void upgradeAndAddAState() {
                // Given a schema that adds the FRUIT state with k/v for A, B, and C
                final var schemaV1 = createV1Schema();
                final var schemaV2 = createV2Schema();

                // When we migrate
                schemaRegistry.register(schemaV1);
                schemaRegistry.register(schemaV2);
                schemaRegistry.migrate(
                        merkleTree,
                        versions[0],
                        versions[2],
                        config,
                        config,
                        new HashMap<>(),
                        migrationStateChanges,
                        startupNetworks,
                        TEST_PLATFORM_STATE_FACADE);

                // We should see the v2 state (the delta from v2 after applied atop v1)
                final var readableStates = merkleTree.getReadableStates(FIRST_SERVICE);
                assertThat(readableStates.size()).isEqualTo(3);

                final ReadableKVState<ProtoBytes, ProtoBytes> fruitV2 = readableStates.get(FRUIT_STATE_ID);
                assertThat(fruitV2.keys()).toIterable().containsExactlyInAnyOrder(B_KEY, C_KEY, E_KEY);
                assertThat(fruitV2.get(B_KEY)).isEqualTo(BLACKBERRY);

                final ReadableKVState<ProtoBytes, ProtoBytes> learningV2 = readableStates.get(STEAM_STATE_ID);
                assertThat(learningV2.get(A_KEY)).isEqualTo(ART);
                assertThat(learningV2.get(B_KEY)).isEqualTo(BIOLOGY);

                final ReadableSingletonState<ProtoBytes> countryV2 = readableStates.getSingleton(COUNTRY_STATE_ID);
                assertThat(countryV2.get()).isEqualTo(BRAZIL);
            }

            @Test
            @DisplayName("Migration from a former version and remove a state")
            void upgradeWithARemoveStep() {
                // Given a schema that adds the FRUIT state with k/v for A, B, and C
                final var schemaV1 = createV1Schema();
                final var schemaV2 = createV2Schema();
                final var schemaV3 = createV3Schema();

                // When we migrate
                schemaRegistry.register(schemaV1);
                schemaRegistry.register(schemaV2);
                schemaRegistry.register(schemaV3);
                schemaRegistry.migrate(
                        merkleTree,
                        versions[0],
                        versions[3],
                        config,
                        config,
                        new HashMap<>(),
                        migrationStateChanges,
                        startupNetworks,
                        TEST_PLATFORM_STATE_FACADE);

                // We should see the v3 state (the delta from v3 after applied atop v2 and v1)
                final var readableStates = merkleTree.getReadableStates(FIRST_SERVICE);
                assertThat(readableStates.size()).isEqualTo(1);
                assertThat(readableStates.stateIds()).containsExactlyInAnyOrder(STEAM_STATE_ID);

                // This should be deleted
                assertThatThrownBy(() -> readableStates.get(FRUIT_STATE_ID))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> readableStates.getSingleton(COUNTRY_STATE_ID))
                        .isInstanceOf(IllegalArgumentException.class);

                // And this should be updated
                final ReadableKVState<ProtoBytes, ProtoBytes> learningV2 = readableStates.get(STEAM_STATE_ID);
                assertThat(learningV2.get(A_KEY)).isEqualTo(ART);
                assertThat(learningV2.get(B_KEY)).isEqualTo(BIOLOGY);
                assertThat(learningV2.get(C_KEY)).isEqualTo(CHEMISTRY);
            }

            @Test
            @DisplayName("If a schema migration fails, all migrations stop")
            void badSchema() {
                // Given a bad schema followed by a good one
                final var schemaV2Called = new AtomicBoolean(false);
                final var schemaV1 = new TestSchema(versions[1], () -> {
                    throw new RuntimeException("Bad");
                });
                final var schemaV2 = new TestSchema(versions[2], () -> schemaV2Called.set(true));

                // When we migrate
                schemaRegistry.register(schemaV1);
                schemaRegistry.register(schemaV2);

                // We should see that the migration failed
                assertThatThrownBy(() -> schemaRegistry.migrate(
                                merkleTree,
                                versions[0],
                                versions[2],
                                config,
                                config,
                                new HashMap<>(),
                                migrationStateChanges,
                                startupNetworks,
                                TEST_PLATFORM_STATE_FACADE))
                        .isInstanceOf(RuntimeException.class)
                        .hasMessage("Bad");

                // And we should see that schemaV2Called is false because it was never called
                assertThat(schemaV2Called).isFalse();
            }
        }
    }
}
