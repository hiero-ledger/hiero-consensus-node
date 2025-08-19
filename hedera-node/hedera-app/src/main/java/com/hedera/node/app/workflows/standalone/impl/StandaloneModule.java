// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.standalone.impl;

import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.BACKEND_THROTTLE;
import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.NOOP_THROTTLE;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.annotations.NodeSelfId;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.metrics.StoreMetricsService;
import com.hedera.node.app.throttle.ThrottleAccumulator;
import com.hedera.node.app.throttle.ThrottleMetrics;
import com.hedera.node.app.throttle.annotations.BackendThrottle;
import com.hedera.node.config.ConfigProvider;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.platform.state.service.SnapshotPlatformStateAccessor;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import javax.inject.Singleton;

@Module
public interface StandaloneModule {
    @Provides
    @Nullable
    @Singleton
    static AtomicBoolean provideMaybeSystemEntitiesCreatedFlag() {
        return null;
    }

    @Binds
    @Singleton
    NetworkInfo bindNetworkInfo(@NonNull StandaloneNetworkInfo simulatedNetworkInfo);

    @Provides
    @Singleton
    static IntSupplier provideFrontendThrottleSplit() {
        return () -> 1;
    }

    @Provides
    @Singleton
    @BackendThrottle
    static ThrottleAccumulator provideBackendThrottleAccumulator(
            @NonNull final ConfigProvider configProvider,
            final boolean disableThrottling,
            @NonNull final Metrics metrics) {
        final var throttleMetrics = new ThrottleMetrics(metrics, BACKEND_THROTTLE);
        return new ThrottleAccumulator(
                () -> 1,
                configProvider::getConfiguration,
                disableThrottling ? NOOP_THROTTLE : BACKEND_THROTTLE,
                throttleMetrics,
                ThrottleAccumulator.Verbose.YES);
    }

    @Provides
    @Singleton
    static PlatformStateAccessor providePlatformState() {
        return new SnapshotPlatformStateAccessor(PlatformState.DEFAULT);
    }

    @Provides
    @Singleton
    static InstantSource provideInstantSource() {
        return InstantSource.system();
    }

    @Provides
    @Singleton
    @NodeSelfId
    static AccountID provideNodeSelfId(EntityIdFactory entityIdFactory) {
        // This is only used to check the shard and realm of account ids
        return entityIdFactory.newDefaultAccountId();
    }

    @Provides
    @Singleton
    static StoreMetricsService provideStoreMetricsService(Metrics metrics) {
        return new StoreMetricsServiceImpl(metrics);
    }
}
