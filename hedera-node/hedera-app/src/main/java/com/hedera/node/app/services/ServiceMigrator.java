// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Defines a type able to perform some related set of migrations on a {@link State} instance
 * given a current and previous version of the state; a configuration; and network information, and
 * the metrics that services will use.
 */
public interface ServiceMigrator {
    /**
     * Perform the migrations on the given state.
     *
     * @param state The state to migrate
     * @param servicesRegistry The services registry to use for the migrations
     * @param previousVersion The previous version of the state
     * @param currentVersion The current version of the state
     * @param appConfig The app configuration to use for the migrations
     * @param platformConfig The platform configuration to use for subsequent object initializations
     * @param startupNetworks The startup networks to use for the migrations
     * @param storeMetricsService The store metrics service to use for the migrations
     * @param configProvider The config provider to use for the migrations
     * @param platformStateFacade The facade object to access platform state properties
     * @return The list of builders for state changes that occurred during the migrations
     */
    List<StateChanges.Builder> doMigrations(
            @NonNull MerkleNodeState state,
            @NonNull ServicesRegistry servicesRegistry,
            @Nullable SemanticVersion previousVersion,
            @NonNull SemanticVersion currentVersion,
            @NonNull Configuration appConfig,
            @NonNull Configuration platformConfig,
            @NonNull StartupNetworks startupNetworks,
            @NonNull StoreMetricsServiceImpl storeMetricsService,
            @NonNull ConfigProviderImpl configProvider,
            @NonNull PlatformStateFacade platformStateFacade);
}
