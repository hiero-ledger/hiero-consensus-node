// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.config.ConfigProvider;
import com.swirlds.metrics.api.Metrics;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ExecutorService;
import javax.inject.Singleton;
import org.hiero.interledger.clpr.impl.client.ClprConnectionManager;

@Module
public interface ClprModule {
    @Provides
    @Singleton
    static ClprEndpoint provideClprEndpoint(
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider,
            @NonNull final ExecutorService executor,
            @NonNull final ClprStateProofManager stateProofManager,
            @NonNull final Metrics metrics,
            @NonNull final ClprConnectionManager clprConnectionManager) {
        return new ClprEndpoint(
                networkInfo, configProvider, executor, stateProofManager, metrics, clprConnectionManager);
    }
}
