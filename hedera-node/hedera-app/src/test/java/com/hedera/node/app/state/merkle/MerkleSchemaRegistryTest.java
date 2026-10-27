// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle;

import static com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils.createTestState;
import static com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils.createTestStateWithVM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.services.MigrationStateChanges;
import com.hedera.node.app.spi.fixtures.TestSchema;
import com.hedera.node.app.spi.migrate.HederaMigrationContext;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.config.data.HederaConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.test.fixtures.merkle.MerkleTestBase;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

            // Then on migrateFromV9ToV10, it is restarted
            migrateFromV9ToV10();
            Mockito.verify(schema, Mockito.times(1)).restart(Mockito.any());
        }

        @Test
        @DisplayName("Registering with the same schema twice")
        void registerTwice() {
            // Given a schema
            final var schema = Mockito.spy(new TestSchema(10));

            // When it is registered twice
            schemaRegistry.register(schema);
            schemaRegistry.register(schema);

            // Then on migrateFromV9ToV10, it is restarted only once
            migrateFromV9ToV10();
            Mockito.verify(schema, Mockito.times(1)).restart(Mockito.any());
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
            Mockito.verify(schema1, Mockito.times(0)).restart(Mockito.any());
            Mockito.verify(schema2, Mockito.times(1)).restart(Mockito.any());
        }

        /**
         * Utility method that migrates from version 9 to 10
         */
        void migrateFromV9ToV10() {
            final var virtualMap = VirtualMapUtils.createVirtualMap(FILE_SYSTEM_MANAGER);
            SemanticVersion latestVersion = version(10, 0, 0);
            schemaRegistry.migrate(
                    createTestStateWithVM(virtualMap),
                    version(9, 0, 0),
                    latestVersion,
                    config,
                    config,
                    new HashMap<>(),
                    migrationStateChanges,
                    startupNetworks,
                    InitTrigger.RESTART);
            virtualMap.release();
        }
    }

    @Nested
    @DisplayName("Migration Tests")
    class MigrationTest {
        private VirtualMapState merkleTree;
        private SemanticVersion[] versions;

        @BeforeEach
        void setUp() {

            // Let the first version[0] be null, and all others have a number
            versions = new SemanticVersion[10];
            for (int i = 1; i < versions.length; i++) {
                versions[i] = version(0, i, 0);
            }
            merkleTree = createTestState();
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
                            InitTrigger.RESTART))
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
                            InitTrigger.RESTART))
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
                            InitTrigger.GENESIS))
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
                            InitTrigger.RESTART))
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
                            InitTrigger.RESTART))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("The latest schema is restarted if previousVersion == currentVersion")
        void restartIsCalledIfVersionsAreTheSame() {
            // Given a schema
            final var schema = Mockito.spy(new TestSchema(versions[1]));

            // When it is registered and migrate is called
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
                    InitTrigger.RESTART);

            // Then it is restarted
            Mockito.verify(schema, Mockito.times(1)).restart(Mockito.any());
        }

        @Test
        @DisplayName("The latest schema is restarted if its version is before the current software version")
        void restartIsCalledIfSchemaVersionIsBeforeCurrentVersion() {
            // Given a schema
            final var schema = Mockito.spy(new TestSchema(versions[1]));

            // When it is registered and migrate is called
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
                    InitTrigger.RESTART);

            // Then it is restarted
            Mockito.verify(schema, Mockito.times(1)).restart(Mockito.any());
        }

        @Test
        @DisplayName("The latest schema is restarted at genesis")
        void restartIsCalledAtGenesis() {
            // Given a schema
            final var schema = Mockito.spy(new TestSchema(versions[1]));

            // When it is registered and migrate is called with no previous version
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
                    InitTrigger.GENESIS);

            // Then it is restarted
            Mockito.verify(schema, Mockito.times(1)).restart(Mockito.any());
        }

        @Test
        @DisplayName("Only the latest schema is restarted, even when schemas skip versions")
        void onlyLatestSchemaIsRestarted() {
            // We will place into this list each schema as it is restarted
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
                    InitTrigger.GENESIS);

            // Then only v6 is restarted
            assertThat(called).containsExactly(versions[6]);
        }

        @ParameterizedTest
        @EnumSource(
                value = InitTrigger.class,
                names = {"GENESIS", "RESTART", "RECONNECT"})
        @DisplayName("Every schema is applied even when its version is later than the software and state versions")
        void schemasNewerThanSoftwareVersionAreApplied(final InitTrigger trigger) {
            // Given schemas whose versions are both later than the software version
            final var softwareVersion = version(0, 1, 0);
            final var previousVersion = trigger == InitTrigger.GENESIS ? null : softwareVersion;
            final var restarted = new AtomicBoolean(false);
            final var schemaV49 = new TestSchema(
                    version(0, 49, 0),
                    Set.of(StateDefinition.keyValue(
                            FRUIT_STATE_ID, FRUIT_STATE_KEY, ProtoBytes.PROTOBUF, ProtoBytes.PROTOBUF)),
                    Set.of(),
                    () -> {
                        throw new AssertionError("Only the latest schema should be restarted");
                    });
            final var schemaV50 =
                    new TestSchema(
                            version(0, 50, 0),
                            Set.of(StateDefinition.singleton(COUNTRY_STATE_ID, COUNTRY_STATE_KEY, ProtoBytes.PROTOBUF)),
                            Set.of(),
                            null) {
                        @Override
                        public void restart(@NonNull final MigrationContext ctx) {
                            restarted.set(true);
                            assertThat(ctx.previousVersion()).isEqualTo(previousVersion);
                            assertThat(ctx.isGenesis()).isEqualTo(trigger == InitTrigger.GENESIS);
                            assertThat(ctx).isInstanceOfSatisfying(HederaMigrationContext.class, hederaContext -> {
                                assertThat(hederaContext.isReconnect()).isEqualTo(trigger == InitTrigger.RECONNECT);
                            });
                        }
                    };
            schemaRegistry.register(schemaV49);
            schemaRegistry.register(schemaV50);

            // When the node initializes with software version 0.1.0
            schemaRegistry.migrate(
                    merkleTree,
                    previousVersion,
                    softwareVersion,
                    config,
                    config,
                    new HashMap<>(),
                    migrationStateChanges,
                    startupNetworks,
                    trigger);

            // Then both schemas' states are defined, and only the latest schema is restarted
            final var readableStates = merkleTree.getReadableStates(FIRST_SERVICE);
            assertThat(readableStates.stateIds()).containsExactlyInAnyOrder(FRUIT_STATE_ID, COUNTRY_STATE_ID);
            assertThat(restarted).isTrue();
        }

        /**
         * In these tests, each release's latest schema will apply some kind of state change to the
         * tree from its restart hook, as a state is created at genesis and then upgraded.
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
                        final var fruitDef = StateDefinition.keyValue(
                                FRUIT_STATE_ID, FRUIT_STATE_KEY, ProtoBytes.PROTOBUF, ProtoBytes.PROTOBUF);
                        return Set.of(fruitDef);
                    }

                    @Override
                    public void restart(@NonNull final MigrationContext ctx) {
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
                        final var learningDef = StateDefinition.keyValue(
                                STEAM_STATE_ID, STEAM_STATE_KEY, ProtoBytes.PROTOBUF, ProtoBytes.PROTOBUF);
                        final var countryDef =
                                StateDefinition.singleton(COUNTRY_STATE_ID, COUNTRY_STATE_KEY, ProtoBytes.PROTOBUF);
                        return Set.of(learningDef, countryDef);
                    }

                    @Override
                    public void restart(@NonNull final MigrationContext ctx) {
                        assertThat(ctx).isNotNull();
                        final var previousStates = ctx.previousStates();
                        final var newStates = ctx.newStates();

                        // First check that the previous states only includes what was there before,
                        // and nothing new
                        assertThat(previousStates.isEmpty()).isFalse();
                        assertThat(previousStates.contains(FRUIT_STATE_ID)).isTrue();
                        final ReadableKVState<ProtoBytes, ProtoBytes> oldFruit = previousStates.get(FRUIT_STATE_ID);
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
                    public void restart(@NonNull MigrationContext ctx) {
                        assertThat(ctx).isNotNull();
                        final var previousStates = ctx.previousStates();
                        final var newStates = ctx.newStates();

                        // Verify that everything in v2 is still here
                        assertThat(previousStates.stateIds())
                                .containsExactlyInAnyOrder(FRUIT_STATE_ID, STEAM_STATE_ID, COUNTRY_STATE_ID);
                        final ReadableKVState<ProtoBytes, ProtoBytes> oldFruit = previousStates.get(FRUIT_STATE_ID);
                        assertThat(oldFruit.get(B_KEY)).isEqualTo(BLACKBERRY);
                        assertThat(oldFruit.get(C_KEY)).isEqualTo(CHERRY);
                        assertThat(oldFruit.get(E_KEY)).isEqualTo(EGGPLANT);
                        final ReadableKVState<ProtoBytes, ProtoBytes> oldLearning = previousStates.get(STEAM_STATE_ID);
                        assertThat(oldLearning.get(A_KEY)).isEqualTo(ART);
                        assertThat(oldLearning.get(B_KEY)).isEqualTo(BIOLOGY);

                        // Now check that the new states contains only the state not being removed
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

            /**
             * Applies the given schemas as the complete set registered by a release, just as a node
             * running that release would at startup.
             */
            void applyRelease(
                    final SemanticVersion previousVersion,
                    final SemanticVersion currentVersion,
                    final Schema... releaseSchemas) {
                final var releaseRegistry = new MerkleSchemaRegistry(FIRST_SERVICE, new SchemaApplications());
                for (final var schema : releaseSchemas) {
                    releaseRegistry.register(schema);
                }
                releaseRegistry.migrate(
                        merkleTree,
                        previousVersion,
                        currentVersion,
                        config,
                        config,
                        new HashMap<>(),
                        migrationStateChanges,
                        startupNetworks,
                        previousVersion == null ? InitTrigger.GENESIS : InitTrigger.RESTART);
            }

            @Test
            @DisplayName("Restart at genesis sees nothing in oldStates but can insert into new states")
            void genesis() {
                // Given a release whose only schema adds the FRUIT state with k/v for A, B, and C
                applyRelease(versions[0], versions[1], createV1Schema());

                // Then we see that the values for A, B, and C are available
                final var readableStates = merkleTree.getReadableStates(FIRST_SERVICE);
                assertThat(readableStates.size()).isEqualTo(1);
                final ReadableKVState<ProtoBytes, ProtoBytes> fruitV1 = readableStates.get(FRUIT_STATE_ID);
                assertThat(fruitV1.get(A_KEY)).isEqualTo(APPLE);
                assertThat(fruitV1.get(B_KEY)).isEqualTo(BANANA);
                assertThat(fruitV1.get(C_KEY)).isEqualTo(CHERRY);
            }

            @Test
            @DisplayName("Upgrade from a former release and add a new state")
            void upgradeAndAddAState() {
                // Given a genesis state created by a release with only the v1 schema
                applyRelease(versions[0], versions[1], createV1Schema());

                // When we upgrade to a release with both schemas
                applyRelease(versions[1], versions[2], createV1Schema(), createV2Schema());

                // We should see the v2 state (the delta from v2 after applied atop v1)
                final var readableStates = merkleTree.getReadableStates(FIRST_SERVICE);
                assertThat(readableStates.size()).isEqualTo(3);

                final ReadableKVState<ProtoBytes, ProtoBytes> fruitV2 = readableStates.get(FRUIT_STATE_ID);
                assertThat(fruitV2.get(B_KEY)).isEqualTo(BLACKBERRY);

                final ReadableKVState<ProtoBytes, ProtoBytes> learningV2 = readableStates.get(STEAM_STATE_ID);
                assertThat(learningV2.get(A_KEY)).isEqualTo(ART);
                assertThat(learningV2.get(B_KEY)).isEqualTo(BIOLOGY);

                final ReadableSingletonState<ProtoBytes> countryV2 = readableStates.getSingleton(COUNTRY_STATE_ID);
                assertThat(countryV2.get()).isEqualTo(BRAZIL);
            }

            @Test
            @DisplayName("Upgrade from a former release and remove a state")
            void upgradeWithARemoveStep() {
                // Given a state created at genesis by the v1 release and upgraded to the v2 release
                applyRelease(versions[0], versions[1], createV1Schema());
                applyRelease(versions[1], versions[2], createV1Schema(), createV2Schema());

                // When we upgrade to a release with all three schemas
                applyRelease(versions[2], versions[3], createV1Schema(), createV2Schema(), createV3Schema());

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
            @DisplayName("If the latest schema's restart fails, the failure propagates")
            void badSchema() {
                // Given a good schema followed by a bad one
                final var schemaV1 = createV1Schema();
                final var schemaV2 = new TestSchema(versions[2], () -> {
                    throw new RuntimeException("Bad");
                });

                // When we migrate
                schemaRegistry.register(schemaV1);
                schemaRegistry.register(schemaV2);

                // We should see that the migration failed
                assertThatThrownBy(() -> schemaRegistry.migrate(
                                merkleTree,
                                versions[1],
                                versions[2],
                                config,
                                config,
                                new HashMap<>(),
                                migrationStateChanges,
                                startupNetworks,
                                InitTrigger.RESTART))
                        .isInstanceOf(RuntimeException.class)
                        .hasMessage("Bad");
            }

            @Test
            @DisplayName("A state removed by a later schema is visible to its restart, and then removed")
            void stateRemovedByLaterSchemaIsVisibleToItsRestart() {
                // Given a schema V1 that adds FRUIT_STATE_ID
                final var schemaV1 = createV1Schema();
                // And a schema V2 that removes FRUIT_STATE_ID
                final var sawFruit = new AtomicBoolean(false);
                final var schemaV2 = new TestSchema(versions[2]) {
                    @NonNull
                    @Override
                    public Set<Integer> statesToRemove() {
                        return Set.of(FRUIT_STATE_ID);
                    }

                    @Override
                    public void restart(@NonNull final MigrationContext ctx) {
                        sawFruit.set(ctx.previousStates().contains(FRUIT_STATE_ID));
                        assertThat(ctx.newStates().contains(FRUIT_STATE_ID)).isFalse();
                    }
                };

                schemaRegistry.register(schemaV1);
                schemaRegistry.register(schemaV2);

                // When we migrate from versions[2] to versions[2] (so the state "already includes" v2)
                schemaRegistry.migrate(
                        merkleTree,
                        versions[2],
                        versions[2],
                        config,
                        config,
                        new HashMap<>(),
                        migrationStateChanges,
                        startupNetworks,
                        InitTrigger.RESTART);

                // Then the removing schema's restart could still see the state it removes
                assertThat(sawFruit).isTrue();
                // But it is no longer part of the service's states
                final var readableStates = merkleTree.getReadableStates(FIRST_SERVICE);
                assertThat(readableStates.contains(FRUIT_STATE_ID)).isFalse();
            }
        }
    }
}
