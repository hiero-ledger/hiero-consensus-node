// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fixtures.state;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.services.ServiceMigrator;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.config.data.HederaConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.StartupNetworks;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.test.fixtures.MapWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class FakeServiceMigrator implements ServiceMigrator {
    private static final String NAME_OF_ENTITY_ID_SERVICE = "EntityIdService";
    private static final String NAME_OF_ENTITY_ID_SINGLETON = "ENTITY_ID";

    @Override
    public List<StateChanges.Builder> doMigrations(
            @NonNull final State state,
            @NonNull final ServicesRegistry servicesRegistry,
            @Nullable final SoftwareVersion previousVersion,
            @NonNull final SoftwareVersion currentVersion,
            @NonNull final Configuration appConfig,
            @NonNull final Configuration platformConfig,
            @Nullable final NetworkInfo genesisNetworkInfo,
            @NonNull final Metrics metrics,
            @NonNull final StartupNetworks startupNetworks,
            @NonNull final StoreMetricsServiceImpl storeMetricsService,
            @NonNull final ConfigProviderImpl configProvider,
            @NonNull final PlatformStateFacade platformStateFacade) {
        requireNonNull(state);
        requireNonNull(servicesRegistry);
        requireNonNull(currentVersion);
        requireNonNull(appConfig);
        requireNonNull(platformConfig);
        requireNonNull(genesisNetworkInfo);
        requireNonNull(metrics);

        if (!(state instanceof FakeState fakeState)) {
            throw new IllegalArgumentException("Can only be used with FakeState instances");
        }
        if (!(servicesRegistry instanceof FakeServicesRegistry registry)) {
            throw new IllegalArgumentException("Can only be used with FakeServicesRegistry instances");
        }

        final AtomicLong prevEntityNum =
                new AtomicLong(appConfig.getConfigData(HederaConfig.class).firstUserEntity() - 1);
        final Map<String, Object> sharedValues = new HashMap<>();
        final var entityIdRegistration = registry.registrations().stream()
                .filter(service ->
                        NAME_OF_ENTITY_ID_SERVICE.equals(service.service().getServiceName()))
                .findFirst()
                .orElseThrow();
        if (!(entityIdRegistration.registry() instanceof FakeSchemaRegistry entityIdRegistry)) {
            throw new IllegalArgumentException("Can only be used with FakeSchemaRegistry instances");
        }
        final var deserializedPbjVersion = Optional.ofNullable(previousVersion)
                .map(SoftwareVersion::getPbjSemanticVersion)
                .orElse(null);
        entityIdRegistry.migrate(
                NAME_OF_ENTITY_ID_SERVICE,
                fakeState,
                deserializedPbjVersion,
                genesisNetworkInfo,
                appConfig,
                platformConfig,
                sharedValues,
                prevEntityNum,
                startupNetworks);
        registry.registrations().stream()
                .filter(r -> !Objects.equals(entityIdRegistration, r))
                .forEach(registration -> {
                    if (!(registration.registry() instanceof FakeSchemaRegistry schemaRegistry)) {
                        throw new IllegalArgumentException("Can only be used with FakeSchemaRegistry instances");
                    }
                    schemaRegistry.migrate(
                            registration.serviceName(),
                            fakeState,
                            deserializedPbjVersion,
                            genesisNetworkInfo,
                            appConfig,
                            platformConfig,
                            sharedValues,
                            prevEntityNum,
                            startupNetworks);
                });
        final var entityIdWritableStates = fakeState.getWritableStates(NAME_OF_ENTITY_ID_SERVICE);
        if (!(entityIdWritableStates instanceof MapWritableStates mapWritableStates)) {
            throw new IllegalArgumentException("Can only be used with MapWritableStates instances");
        }
        mapWritableStates.getSingleton(NAME_OF_ENTITY_ID_SINGLETON).put(new EntityNumber(prevEntityNum.get()));
        mapWritableStates.commit();
        return List.of();
    }
}
