// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_KEY;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;
import static com.swirlds.state.test.fixtures.merkle.TestSchema.CURRENT_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.primitives.ProtoString;
import com.hedera.node.app.HederaStateRoot;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.services.OrderedServiceMigrator;
import com.hedera.node.app.services.ServicesRegistryImpl;
import com.hedera.node.app.version.ServicesSoftwareVersion;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.test.fixtures.state.MerkleTestBase;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.lifecycle.StartupNetworks;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DependencyMigrationTest extends MerkleTestBase {
    private static final VersionedConfigImpl VERSIONED_CONFIG =
            new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), 1);
    private static final long INITIAL_ENTITY_ID = 5;
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(59).patch(0).build();

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private StartupNetworks startupNetworks;

    private StoreMetricsServiceImpl storeMetricsService;

    private ConfigProviderImpl configProvider;

    private HederaStateRoot merkleTree;

    @BeforeEach
    void setUp() {
        registry = mock(ConstructableRegistry.class);
        merkleTree = new HederaStateRoot();
        configProvider = new ConfigProviderImpl();
        storeMetricsService = new StoreMetricsServiceImpl(new NoOpMetrics());
    }

    @Nested
    @SuppressWarnings("DataFlowIssue")
    @ExtendWith(MockitoExtension.class)
    final class DoMigrationsNullParams {
        @Mock
        private ServicesRegistryImpl servicesRegistry;

        @Test
        void stateRequired() {
            final var subject = new OrderedServiceMigrator();
            Assertions.assertThatThrownBy(() -> subject.doMigrations(
                            null,
                            servicesRegistry,
                            null,
                            new ServicesSoftwareVersion(CURRENT_VERSION),
                            VERSIONED_CONFIG,
                            VERSIONED_CONFIG,
                            networkInfo,
                            mock(Metrics.class),
                            startupNetworks,
                            storeMetricsService,
                            configProvider,
                            TEST_PLATFORM_STATE_FACADE))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void currentVersionRequired() {
            final var subject = new OrderedServiceMigrator();
            Assertions.assertThatThrownBy(() -> subject.doMigrations(
                            merkleTree,
                            servicesRegistry,
                            null,
                            null,
                            VERSIONED_CONFIG,
                            VERSIONED_CONFIG,
                            networkInfo,
                            mock(Metrics.class),
                            startupNetworks,
                            storeMetricsService,
                            configProvider,
                            TEST_PLATFORM_STATE_FACADE))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void configRequired2() {
            final var subject = new OrderedServiceMigrator();
            Assertions.assertThatThrownBy(() -> subject.doMigrations(
                            merkleTree,
                            servicesRegistry,
                            null,
                            new ServicesSoftwareVersion(CURRENT_VERSION),
                            null,
                            null,
                            networkInfo,
                            mock(Metrics.class),
                            startupNetworks,
                            storeMetricsService,
                            configProvider,
                            TEST_PLATFORM_STATE_FACADE))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void metricsRequired() {
            final var subject = new OrderedServiceMigrator();
            Assertions.assertThatThrownBy(() -> subject.doMigrations(
                            merkleTree,
                            servicesRegistry,
                            null,
                            new ServicesSoftwareVersion(CURRENT_VERSION),
                            VERSIONED_CONFIG,
                            VERSIONED_CONFIG,
                            networkInfo,
                            null,
                            startupNetworks,
                            storeMetricsService,
                            configProvider,
                            TEST_PLATFORM_STATE_FACADE))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    @DisplayName("Genesis inter-service dependency migration works")
    void genesisWithNullVersion() {
        // Given: register the EntityIdService and the DependentService (order of registration shouldn't matter)
        final var servicesRegistry = new ServicesRegistryImpl(registry, DEFAULT_CONFIG);
        final var entityService = new EntityIdService() {
            @Override
            public void registerSchemas(@NonNull final SchemaRegistry registry) {
                registry.register(new Schema(VERSION) {
                    @NonNull
                    @Override
                    public Set<StateDefinition> statesToCreate() {
                        return Set.of(
                                StateDefinition.singleton(ENTITY_ID_STATE_KEY, EntityNumber.PROTOBUF),
                                StateDefinition.singleton(ENTITY_COUNTS_KEY, EntityCounts.PROTOBUF));
                    }

                    public void migrate(@NonNull MigrationContext ctx) {
                        final var entityIdState = ctx.newStates().getSingleton(ENTITY_ID_STATE_KEY);
                        entityIdState.put(new EntityNumber(INITIAL_ENTITY_ID));
                        final var entityCountsState = ctx.newStates().getSingleton(ENTITY_COUNTS_KEY);
                        entityCountsState.put(EntityCounts.DEFAULT);
                    }
                });
            }
        };
        final DependentService dsService = new DependentService();
        Set.of(entityService, dsService).forEach(servicesRegistry::register);

        // When: the migrations are run
        final var subject = new OrderedServiceMigrator();
        subject.doMigrations(
                merkleTree,
                servicesRegistry,
                null,
                new ServicesSoftwareVersion(
                        SemanticVersion.newBuilder().major(2).build()),
                VERSIONED_CONFIG,
                VERSIONED_CONFIG,
                networkInfo,
                mock(Metrics.class),
                startupNetworks,
                storeMetricsService,
                configProvider,
                TEST_PLATFORM_STATE_FACADE);

        // Then: we verify the migrations had the desired effects on both entity ID state and DependentService state
        // First check that the entity ID service has an updated entity ID, despite its schema migration not doing
        // anything except setting the initial entity ID. DependentService's schema #2 should have caused the increments
        // with its new additions to its own state.
        final var postMigrationEntityIdState =
                merkleTree.getReadableStates(EntityIdService.NAME).getSingleton(ENTITY_ID_STATE_KEY);
        assertThat(postMigrationEntityIdState.get()).isEqualTo(new EntityNumber(INITIAL_ENTITY_ID + 2));

        // Also verify that both of the DependentService's schema migrations took place. First the initial mappings are
        // created, then the new mappings dependent on the incrementing entity ID are added
        final var postMigrationDsState =
                merkleTree.getReadableStates(DependentService.NAME).get(DependentService.STATE_KEY);
        assertThat(postMigrationDsState.get(new EntityNumber(INITIAL_ENTITY_ID - 1)))
                .isEqualTo(new ProtoString("previously added"));
        assertThat(postMigrationDsState.get(new EntityNumber(INITIAL_ENTITY_ID)))
                .isEqualTo(new ProtoString("last added"));
        assertThat(postMigrationDsState.get(new EntityNumber(INITIAL_ENTITY_ID + 1)))
                .isEqualTo(new ProtoString("newly-added 1"));
        assertThat(postMigrationDsState.get(new EntityNumber(INITIAL_ENTITY_ID + 2)))
                .isEqualTo(new ProtoString("newly-added 2"));
    }

    @Test
    @DisplayName("Service migrations are ordered as expected")
    void expectedMigrationOrdering() {
        final var orderedInvocations = new LinkedList<>();

        // Given: register four services, each with their own schema migration, that will add an object to
        // orderedInvocations during migration. We'll do this to track the order of the service migrations
        final var servicesRegistry = new ServicesRegistryImpl(registry, DEFAULT_CONFIG);
        // Define the Entity ID Service:
        final EntityIdService entityIdService = new EntityIdService() {
            @Override
            public void registerSchemas(@NonNull final SchemaRegistry registry) {
                registry.register(new Schema(VERSION) {
                    @NonNull
                    public Set<StateDefinition> statesToCreate() {
                        return Set.of(
                                StateDefinition.singleton(ENTITY_ID_STATE_KEY, EntityNumber.PROTOBUF),
                                StateDefinition.singleton(ENTITY_COUNTS_KEY, EntityCounts.PROTOBUF));
                    }

                    public void migrate(@NonNull MigrationContext ctx) {
                        orderedInvocations.add("EntityIdService#migrate");
                    }
                });
            }
        };
        // Define Service A:
        final var serviceA = new Service() {
            @NonNull
            @Override
            public String getServiceName() {
                return "A-Service";
            }

            @Override
            public void registerSchemas(@NonNull final SchemaRegistry registry) {
                registry.register(new Schema(VERSION) {
                    public void migrate(@NonNull MigrationContext ctx) {
                        orderedInvocations.add("A-Service#migrate");
                    }
                });
            }
        };
        // Define Service B:
        final var serviceB = new Service() {
            @NonNull
            @Override
            public String getServiceName() {
                return "B-Service";
            }

            @Override
            public void registerSchemas(@NonNull final SchemaRegistry registry) {
                registry.register(new Schema(VERSION) {
                    public void migrate(@NonNull MigrationContext ctx) {
                        orderedInvocations.add("B-Service#migrate");
                    }
                });
            }
        };
        // Define DependentService:
        final DependentService dsService = new DependentService() {
            @Override
            public void registerSchemas(@NonNull final SchemaRegistry registry) {
                registry.register(new Schema(VERSION) {
                    public void migrate(@NonNull MigrationContext ctx) {
                        orderedInvocations.add("DependentService#migrate");
                    }
                });
            }
        };
        // Intentionally register the services in a different order than the expected migration order
        List.of(dsService, serviceA, entityIdService, serviceB).forEach(servicesRegistry::register);

        // When: the migrations are run
        final var subject = new OrderedServiceMigrator();
        subject.doMigrations(
                merkleTree,
                servicesRegistry,
                null,
                new ServicesSoftwareVersion(
                        SemanticVersion.newBuilder().major(1).build()),
                VERSIONED_CONFIG,
                VERSIONED_CONFIG,
                networkInfo,
                mock(Metrics.class),
                startupNetworks,
                storeMetricsService,
                configProvider,
                TEST_PLATFORM_STATE_FACADE);

        // Then: we verify the migrations were run in the expected order
        Assertions.assertThat(orderedInvocations)
                .containsExactly(
                        // EntityIdService should be migrated first
                        "EntityIdService#migrate",
                        // And the rest are migrated by service name
                        "A-Service#migrate",
                        "B-Service#migrate",
                        "DependentService#migrate");
    }

    // This class represents a service that depends on EntityIdService. This class will create a simple mapping from an
    // entity ID to a string value.
    private static class DependentService implements Service {
        static final String NAME = "TokenService";
        static final String STATE_KEY = "ACCOUNTS";

        @NonNull
        @Override
        public String getServiceName() {
            return NAME;
        }

        public void registerSchemas(@NonNull final SchemaRegistry registry) {
            // Schema #1 - initial schema
            registry.register(new Schema(VERSION) {
                @NonNull
                @Override
                public Set<StateDefinition> statesToCreate() {
                    return Set.of(StateDefinition.inMemory(STATE_KEY, EntityNumber.PROTOBUF, ProtoString.PROTOBUF));
                }

                public void migrate(@NonNull final MigrationContext ctx) {
                    WritableStates dsWritableStates = ctx.newStates();
                    dsWritableStates
                            .get(STATE_KEY)
                            .put(new EntityNumber(INITIAL_ENTITY_ID - 1), new ProtoString("previously added"));
                    dsWritableStates
                            .get(STATE_KEY)
                            .put(new EntityNumber(INITIAL_ENTITY_ID), new ProtoString("last added"));
                }
            });

            // Schema #2 - schema that adds new mappings, dependent on EntityIdService
            registry.register(new Schema(SemanticVersion.newBuilder().major(2).build()) {
                public void migrate(@NonNull final MigrationContext ctx) {
                    final WritableStates dsWritableStates = ctx.newStates();
                    final var newEntityNum1 = ctx.newEntityNumForAccount();
                    dsWritableStates
                            .get(STATE_KEY)
                            .put(new EntityNumber(newEntityNum1), new ProtoString("newly-added 1"));
                    final var newEntityNum2 = ctx.newEntityNumForAccount();
                    dsWritableStates
                            .get(STATE_KEY)
                            .put(new EntityNumber(newEntityNum2), new ProtoString("newly-added 2"));
                }
            });
        }
    }
}
