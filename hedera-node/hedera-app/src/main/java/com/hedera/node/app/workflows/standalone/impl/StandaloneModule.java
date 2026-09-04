// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.standalone.impl;

import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.BACKEND_THROTTLE;
import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.NOOP_THROTTLE;

import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.annotations.LiveConsensusNode;
import com.hedera.node.app.hapi.utils.blocks.NativeTssVerifier;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.metrics.StoreMetricsService;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.node.app.throttle.ThrottleAccumulator;
import com.hedera.node.app.throttle.ThrottleMetrics;
import com.hedera.node.app.throttle.annotations.BackendThrottle;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import javax.inject.Singleton;
import org.hiero.consensus.platformstate.PlatformStateAccessor;
import org.hiero.consensus.platformstate.SnapshotPlatformStateAccessor;

@Module
public interface StandaloneModule {
    @Provides
    @Nullable
    @Singleton
    static AtomicBoolean provideMaybeSystemEntitiesCreatedFlag() {
        return null;
    }

    /**
     * The standalone transaction executor is not a live consensus node. It legitimately dispatches NODE-category
     * transactions (empty signature map) with a caller-chosen payer, so the NODE-payer due-diligence guard in
     * {@code DispatchValidator} must not apply here.
     */
    @Provides
    @Singleton
    @LiveConsensusNode
    static boolean provideIsLiveConsensusNode() {
        return false;
    }

    @Provides
    @Nullable
    @Singleton
    static BlockRecordManager provideNoOpBlockRecordManager() {
        return null;
    }

    /**
     * Provides the {@link TssVerifier} used by CLPR consumers reachable from the standalone
     * executor (e.g. {@code ClprGetLedgerConfigurationHandler}, {@code ClprStateProofManager}).
     * The non-standalone path provides the same binding via {@code ClprSyncWorkflowInjectionModule}.
     */
    @Provides
    @Singleton
    static TssVerifier provideTssVerifier() {
        return new NativeTssVerifier();
    }

    @Binds
    @Singleton
    NetworkInfo bindNetworkInfo(@NonNull StandaloneNetworkInfo simulatedNetworkInfo);

    /**
     * Standalone executor has no signed-block snapshots, so we provide a no-op
     * {@link BlockProvenSnapshotProvider} that always returns empty. CLPR state-proof
     * builders will short-circuit and return {@code null} accordingly.
     */
    @Provides
    @Singleton
    static BlockProvenSnapshotProvider provideNoopBlockProvenSnapshotProvider() {
        return java.util.Optional::empty;
    }

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
    static StoreMetricsService provideStoreMetricsService(Metrics metrics) {
        return new StoreMetricsServiceImpl(metrics);
    }

    @Provides
    @Singleton
    static ClprChannelLifecycle provideNoopClprChannelLifecycle() {
        return new ClprChannelLifecycle() {
            @Override
            public void onChannelActivated(@NonNull final Bytes channelId) {}

            @Override
            public void onChannelClosed(@NonNull final Bytes channelId) {}

            @Override
            public void seedPeerEndpoints(
                    @NonNull final Bytes channelId,
                    @NonNull final java.util.List<com.hedera.hapi.node.state.clpr.ClprEndpoint> endpoints) {}

            @Override
            public void recordPeerObservedManifestVersion(
                    @NonNull final Bytes channelId, final long peerObservedVersion) {}
        };
    }
}
