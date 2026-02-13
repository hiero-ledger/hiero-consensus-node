// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.node.config.ConfigProvider;
import com.swirlds.metrics.api.Metrics;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ExecutorService;
import javax.inject.Singleton;
import org.hiero.interledger.clpr.impl.client.ClprConnectionManager;

/**
 * Dagger module providing CLPR service components.
 */
@Module
public interface ClprModule {
    /**
     * Provides the ClprConfig from the ConfigProvider.
     *
     * @param configProvider provides access to configuration
     * @return ClprConfig instance
     */
    @Provides
    static com.hedera.node.config.data.ClprConfig provideClprConfig(@NonNull final ConfigProvider configProvider) {
        return configProvider.getConfiguration().getConfigData(com.hedera.node.config.data.ClprConfig.class);
    }

    /**
     * Provides the ClprStateProofManager singleton.
     *
     * @param snapshotProvider supplies the latest block-proven snapshot (provided by hedera-app's accessor)
     * @param clprConfig CLPR configuration for dev mode gating
     * @return configured ClprStateProofManager instance
     */
    @Provides
    @Singleton
    static ClprStateProofManager provideClprStateProofManager(
            @NonNull final BlockProvenSnapshotProvider snapshotProvider,
            @NonNull final com.hedera.node.config.data.ClprConfig clprConfig) {
        return new ClprStateProofManager(snapshotProvider, clprConfig);
    }

    /**
     * Provides the ClprEndpoint singleton that manages CLPR protocol operations.
     *
     * @param networkInfo provides information about the network and this node
     * @param configProvider provides access to configuration
     * @param executor thread pool for async operations
     * @param metrics system metrics
     * @param clprConnectionManager manages connections to remote CLPR endpoints
     * @return configured ClprEndpoint instance
     */
    @Provides
    @Singleton
    static ClprEndpointClient provideClprEndpoint(
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider,
            @NonNull final ExecutorService executor,
            @NonNull final Metrics metrics,
            @NonNull final ClprConnectionManager clprConnectionManager,
            @NonNull final ClprStateProofManager stateProofManager // ,
            //            @NonNull final BlockProvenSnapshotProvider snapshotProvider
            ) {
        return new ClprEndpointClient(
                networkInfo, configProvider, executor, metrics, clprConnectionManager, stateProofManager // ,
                //                snapshotProvider
                );
    }
}
