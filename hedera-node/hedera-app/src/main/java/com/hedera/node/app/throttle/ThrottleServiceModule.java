/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.throttle;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.BACKEND_THROTTLE;
import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.FRONTEND_THROTTLE;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.fees.congestion.ThrottleMultiplier;
import com.hedera.node.app.throttle.ThrottleAccumulator.Verbose;
import com.hedera.node.app.throttle.annotations.BackendThrottle;
import com.hedera.node.app.throttle.annotations.CryptoTransferThrottleMultiplier;
import com.hedera.node.app.throttle.annotations.GasThrottleMultiplier;
import com.hedera.node.app.throttle.annotations.IngestThrottle;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.FeesConfig;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntSupplier;
import javax.inject.Singleton;

@Module
public interface ThrottleServiceModule {
    IntSupplier SUPPLY_ONE = () -> 1;

    @Binds
    @Singleton
    NetworkUtilizationManager provideNetworkUtilizationManager(
            NetworkUtilizationManagerImpl networkUtilizationManagerImpl);

    @Provides
    @Singleton
    @BackendThrottle
    static ThrottleAccumulator provideBackendThrottleAccumulator(
            @NonNull final ConfigProvider configProvider,
            @NonNull final Metrics metrics,
            @NonNull Function<SemanticVersion, SoftwareVersion> softwareVersionFactory) {
        final var throttleMetrics = new ThrottleMetrics(metrics, BACKEND_THROTTLE);
        return new ThrottleAccumulator(
                SUPPLY_ONE,
                configProvider::getConfiguration,
                BACKEND_THROTTLE,
                throttleMetrics,
                Verbose.YES,
                softwareVersionFactory);
    }

    @Provides
    @Singleton
    @IngestThrottle
    static ThrottleAccumulator provideIngestThrottleAccumulator(
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider,
            @NonNull final Metrics metrics,
            @NonNull Function<SemanticVersion, SoftwareVersion> softwareVersionFactory) {
        final var throttleMetrics = new ThrottleMetrics(metrics, FRONTEND_THROTTLE);
        final IntSupplier frontendThrottleSplit =
                () -> networkInfo.addressBook().size();
        return new ThrottleAccumulator(
                frontendThrottleSplit,
                configProvider::getConfiguration,
                FRONTEND_THROTTLE,
                throttleMetrics,
                Verbose.YES,
                softwareVersionFactory);
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
