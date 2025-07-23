// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fixtures.state;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.services.ServiceMigrator;
import com.hedera.node.app.services.ServicesRegistry;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.state.lifecycle.StartupNetworks;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeServiceMigrator implements ServiceMigrator {
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
        requireNonNull(servicesRegistry);
        requireNonNull(currentVersion);
        requireNonNull(appConfig);
        requireNonNull(platformConfig);

        if (!(state instanceof FakeState fakeState)) {
            throw new IllegalArgumentException("Can only be used with FakeState instances");
        }
        if (!(servicesRegistry instanceof FakeServicesRegistry registry)) {
            throw new IllegalArgumentException("Can only be used with FakeServicesRegistry instances");
        }
        final Map<String, Object> sharedValues = new HashMap<>();
        registry.registrations().forEach(registration -> {
            if (!(registration.registry() instanceof FakeSchemaRegistry schemaRegistry)) {
                throw new IllegalArgumentException("Can only be used with FakeSchemaRegistry instances");
            }
            schemaRegistry.migrate(
                    registration.serviceName(),
                    fakeState,
                    previousVersion,
                    appConfig,
                    platformConfig,
                    sharedValues,
                    startupNetworks);
        });
        return List.of();
    }
}
