// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.app.state.merkle.MerkleSchemaRegistry;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The entire purpose of this class is to ensure that inter-service dependencies are respected between
 * migrations. The only required dependency right now is the {@link EntityIdService}, which is needed
 * for genesis blocklist accounts in the token service genesis migration. (See {@link
 * Service#registerSchemas(SchemaRegistry)}).
 *
 * <p>Note: there are only two ordering requirements to maintain: first, that the entity ID service
 * is migrated before the token service; and second, that the remaining services are migrated _in any
 * deterministic order_. In order to ensure the entity ID service is migrated before the token service,
 * we'll just migrate the entity ID service first.
 */
public class OrderedServiceMigrator implements ServiceMigrator {
    private static final Logger logger = LogManager.getLogger(OrderedServiceMigrator.class);

    /**
     * Migrates the services registered with the {@link ServicesRegistry}
     *
     * @param state The state to migrate
     * @param servicesRegistry The services registry to use for the migrations
     * @param previousVersion The previous version of the state
     * @param currentVersion The current version of the state
     * @param appConfig The system configuration to use at the time of migration
     * @param platformConfig The platform configuration to use for subsequent object initializations
     * This is only used in genesis case
     * @param startupNetworks The startup networks to use for the migrations
     * @param storeMetricsService The store metrics service to use for the migrations
     * @param configProvider The config provider to use for the migrations
     * @param platformStateFacade The facade class to access platform state
     * @return The list of state changes that occurred during the migrations
     */
    @Override
    public List<StateChanges.Builder> doMigrations(
            @NonNull final MerkleNodeState state,
            @NonNull final ServicesRegistry servicesRegistry,
            @Nullable final SemanticVersion previousVersion,
            @NonNull final SemanticVersion currentVersion,
            @NonNull final Configuration appConfig,
            @NonNull final Configuration platformConfig,
            @NonNull final StartupNetworks startupNetworks,
            @NonNull final StoreMetricsServiceImpl storeMetricsService,
            @NonNull final ConfigProviderImpl configProvider,
            @NonNull final PlatformStateFacade platformStateFacade) {
        requireNonNull(state);
        requireNonNull(currentVersion);
        requireNonNull(appConfig);
        requireNonNull(platformConfig);

        final Map<String, Object> sharedValues = new HashMap<>();
        final var migrationStateChanges = new MigrationStateChanges(state, appConfig, storeMetricsService);
        servicesRegistry.registrations().forEach(registration -> {
            // FUTURE We should have metrics here to keep track of how long it takes to
            // migrate each service
            final var service = registration.service();
            final var serviceName = service.getServiceName();
            logger.info("Migrating Service {}", serviceName);
            final var registry = (MerkleSchemaRegistry) registration.registry();
            registry.migrate(
                    state,
                    previousVersion,
                    currentVersion,
                    appConfig,
                    platformConfig,
                    sharedValues,
                    migrationStateChanges,
                    startupNetworks,
                    platformStateFacade);
        });
        return migrationStateChanges.getStateChanges();
    }
}
