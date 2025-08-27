// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.FRONTEND_THROTTLE;

import com.hedera.node.app.fees.congestion.ThrottleMultiplier;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.throttle.ThrottleAccumulator.Verbose;
import com.hedera.node.app.throttle.annotations.BackendThrottle;
import com.hedera.node.app.throttle.annotations.CryptoTransferThrottleMultiplier;
import com.hedera.node.app.throttle.annotations.GasThrottleMultiplier;
import com.hedera.node.app.throttle.annotations.IngestThrottle;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.FeesConfig;
import com.swirlds.metrics.api.Metrics;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.IntSupplier;
import javax.inject.Singleton;

@Module
public interface ThrottleServiceModule {
    @Binds
    @Singleton
    NetworkUtilizationManager provideNetworkUtilizationManager(
            NetworkUtilizationManagerImpl networkUtilizationManagerImpl);

    @Provides
    @Singleton
    @IngestThrottle
    static ThrottleAccumulator provideIngestThrottleAccumulator(
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider,
            @NonNull final Metrics metrics) {
        final var throttleMetrics = new ThrottleMetrics(metrics, FRONTEND_THROTTLE);
        final IntSupplier frontendThrottleSplit =
                () -> networkInfo.addressBook().size();
        return new ThrottleAccumulator(
                frontendThrottleSplit,
                configProvider::getConfiguration,
                FRONTEND_THROTTLE,
                throttleMetrics,
                Verbose.YES);
    }

    @Provides
    @Singleton
    @CryptoTransferThrottleMultiplier
    static ThrottleMultiplier provideCryptoTransferThrottleMultiplier(
            ConfigProvider configProvider, @BackendThrottle ThrottleAccumulator backendThrottle) {
        return new ThrottleMultiplier(
                "logical TPS",
                "TPS",
                "CryptoTransfer throughput",
                () -> configProvider
                        .getConfiguration()
                        .getConfigData(FeesConfig.class)
                        .minCongestionPeriod(),
                () -> configProvider
                        .getConfiguration()
                        .getConfigData(FeesConfig.class)
                        .percentCongestionMultipliers(),
                () -> backendThrottle.activeThrottlesFor(CRYPTO_TRANSFER));
    }

    @Provides
    @Singleton
    @GasThrottleMultiplier
    static ThrottleMultiplier provideGasThrottleMultiplier(
            ConfigProvider configProvider, @BackendThrottle ThrottleAccumulator backendThrottle) {
        return new ThrottleMultiplier(
                "EVM gas/sec",
                "gas/sec",
                "EVM utilization",
                () -> configProvider
                        .getConfiguration()
                        .getConfigData(FeesConfig.class)
                        .minCongestionPeriod(),
                () -> configProvider
                        .getConfiguration()
                        .getConfigData(FeesConfig.class)
                        .percentCongestionMultipliers(),
                () -> List.of(backendThrottle.gasLimitThrottle()));
    }
}
