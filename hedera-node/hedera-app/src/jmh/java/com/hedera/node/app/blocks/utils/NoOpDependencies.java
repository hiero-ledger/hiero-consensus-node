// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.utils;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.InitialStateHash;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.quiescence.QuiescedHeartbeat;
import com.hedera.node.app.quiescence.QuiescenceController;
import com.hedera.node.app.spi.metrics.StoreMetricsService;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.converter.FunctionalitySetConverter;
import com.hedera.node.config.converter.SemanticVersionConverter;
import com.hedera.node.config.data.*;
import com.hedera.node.config.types.HederaFunctionalitySet;
import com.hedera.node.internal.network.PendingProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricConfig;
import com.swirlds.metrics.api.MetricType;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.State;
import com.swirlds.state.spi.metrics.StoreMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

/**
 * No-op implementations of dependencies for benchmarking BlockStreamManagerImpl.
 */
@SuppressWarnings("deprecation") // Uses deprecated APIs (MetricType, etc.) that are still used in production
public final class NoOpDependencies {

    private NoOpDependencies() {}

    /**
     * Realistic BlockHashSigner that simulates the CPU cost of signing.
     *
     * In production (without TSS), signing is just a SHA-384 hash of the block hash.
     * This implementation simulates that cost by computing the hash asynchronously,
     * matching the production behavior where signing doesn't block the pipeline.
     *
     * This is cleaner than using real TssBlockHashSigner with NoOp services because:
     * - When TSS is disabled, TssBlockHashSigner sets services to null internally anyway
     * - NoOp services would never be used, adding unnecessary complexity
     * - This simulation matches production behavior exactly (SHA-384 hash path)
     */
    public static class RealisticBlockHashSigner implements BlockHashSigner {
        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public Attempt sign(@NonNull Bytes blockHash) {
            // Simulate production behavior: async SHA-384 hash computation
            // This matches TssBlockHashSigner when TSS is disabled (no hintsService)
            return new Attempt(
                    null, // No verification key (TSS disabled)
                    null, // No chain of trust proof (TSS disabled)
                    CompletableFuture.supplyAsync(() -> {
                        // Simulate the CPU cost: SHA-384 hash of block hash
                        // This matches production: noThrowSha384HashOf(blockHash)
                        try {
                            final var digest = java.security.MessageDigest.getInstance("SHA-384");
                            digest.update(blockHash.toByteArray());
                            return Bytes.wrap(digest.digest());
                        } catch (Exception e) {
                            // Fallback: return zero hash if SHA-384 unavailable (shouldn't happen)
                            return Bytes.wrap(new byte[48]); // SHA-384 = 48 bytes
                        }
                    }));
        }
    }

    /** No-op BlockHashSigner (deprecated - use createRealTssBlockHashSigner() for production realism) */
    @Deprecated
    public static class NoOpBlockHashSigner implements BlockHashSigner {
        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public Attempt sign(@NonNull Bytes blockHash) {
            return new Attempt(null, null, CompletableFuture.completedFuture(Bytes.wrap(new byte[64])));
        }
    }

    /** No-op BlockItemWriter */
    public static class NoOpBlockItemWriter implements BlockItemWriter {
        @Override
        public void openBlock(long blockNumber) {}

        @Override
        public void writePbjItemAndBytes(@NonNull BlockItem item, @NonNull Bytes bytes) {}

        @Override
        public void writePbjItem(@NonNull BlockItem item) {}

        @Override
        public void closeCompleteBlock() {}

        @Override
        public void flushPendingBlock(@NonNull PendingProof pendingProof) {}

        @Override
        public void jumpToBlockAfterFreeze(long blockNumber) {}
    }

    /** No-op StoreMetricsService - can be used with real BoundaryStateChangeListener */
    public static class NoOpStoreMetricsService implements StoreMetricsService {
        @Override
        public StoreMetrics get(@NonNull StoreType storeType, long capacity) {
            return count -> {};
        }
    }

    /** Minimal Platform implementation */
    public static class NoOpPlatform implements Platform {
        @Override
        public @NonNull PlatformContext getContext() {
            throw new UnsupportedOperationException("NoOpPlatform.getContext() not implemented");
        }

        @Override
        public @NonNull NotificationEngine getNotificationEngine() {
            throw new UnsupportedOperationException("NoOpPlatform.getNotificationEngine() not implemented");
        }

        @Override
        public @NonNull Roster getRoster() {
            throw new UnsupportedOperationException("NoOpPlatform.getRoster() not implemented");
        }

        @Override
        public @NonNull NodeId getSelfId() {
            throw new UnsupportedOperationException("NoOpPlatform.getSelfId() not implemented");
        }

        @Override
        public @NonNull <T extends State> AutoCloseableWrapper<T> getLatestImmutableState(@NonNull String reason) {
            throw new UnsupportedOperationException("NoOpPlatform.getLatestImmutableState() not implemented");
        }

        @Override
        public @NonNull Signature sign(@NonNull byte[] data) {
            throw new UnsupportedOperationException("NoOpPlatform.sign() not implemented");
        }

        @Override
        public void quiescenceCommand(@NonNull QuiescenceCommand command) {}

        @Override
        public void start() {}

        @Override
        public void destroy() {}
    }

    /** Creates a real QuiescenceController with disabled quiescence for benchmarking */
    public static QuiescenceController createBenchmarkQuiescenceController(@NonNull ConfigProvider configProvider) {
        final var config = configProvider.getConfiguration().getConfigData(QuiescenceConfig.class);
        return new QuiescenceController(config, java.time.Instant::now, () -> 0L);
    }

    /** Creates a no-op InitialStateHash */
    public static InitialStateHash createNoOpInitialStateHash() {
        return new InitialStateHash(CompletableFuture.completedFuture(Bytes.wrap(new byte[48])), 0L);
    }

    /** No-op Lifecycle */
    public static class NoOpLifecycle implements BlockStreamManager.Lifecycle {
        @Override
        public void onOpenBlock(@NonNull State state) {}

        @Override
        public void onCloseBlock(@NonNull State state) {}
    }

    /** Creates a QuiescedHeartbeat using real QuiescenceController but NoOpPlatform */
    public static QuiescedHeartbeat createBenchmarkQuiescedHeartbeat(
            @NonNull QuiescenceController quiescenceController) {
        return new QuiescedHeartbeat(quiescenceController, new NoOpPlatform());
    }

    /** Creates a real BoundaryStateChangeListener for benchmarking */
    public static BoundaryStateChangeListener createBenchmarkBoundaryStateChangeListener(
            @NonNull ConfigProvider configProvider) {
        return new BoundaryStateChangeListener(new NoOpStoreMetricsService(), configProvider::getConfiguration);
    }

    /** No-op Counter */
    public static class NoOpCounter implements Counter {
        @Override
        public long get() {
            return 0;
        }

        @Override
        public void add(final long value) {
            // No-op
        }

        @Override
        public void increment() {
            // No-op
        }

        @Override
        public @NonNull String getCategory() {
            return "noop";
        }

        @Override
        public @NonNull String getName() {
            return "noop-counter";
        }

        @Override
        public @NonNull String getDescription() {
            return "No-op counter";
        }

        @Override
        public @NonNull String getUnit() {
            return "";
        }

        @Override
        public @NonNull String getFormat() {
            return "%d";
        }

        @Override
        @SuppressWarnings("deprecation") // MetricType is deprecated but still required by Counter interface
        public @NonNull MetricType getMetricType() {
            return MetricType.COUNTER;
        }

        @Override
        public @NonNull Long get(@NonNull final ValueType valueType) {
            return 0L;
        }

        @Override
        public void reset() {
            // No-op
        }
    }

    /** No-op Metrics */
    public static class NoOpMetrics implements Metrics {
        @Override
        public Metric getMetric(@NonNull String category, @NonNull String name) {
            return null;
        }

        @Override
        public @NonNull Collection<Metric> findMetricsByCategory(@NonNull String category) {
            return Collections.emptyList();
        }

        @Override
        public @NonNull Collection<Metric> getAll() {
            return Collections.emptyList();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Metric> @NonNull T getOrCreate(final @NonNull MetricConfig<T, ?> config) {
            // Check if this is a Counter config and return a NoOpCounter
            if (config.getResultClass() == Counter.class) {
                return (T) new NoOpCounter();
            }
            // For other metric types, throw exception (can be extended later if needed)
            throw new UnsupportedOperationException("NoOpMetrics.getOrCreate() not implemented for "
                    + config.getResultClass().getSimpleName());
        }

        @Override
        public void remove(@NonNull MetricConfig<?, ?> config) {}

        @Override
        public void remove(@NonNull Metric metric) {}

        @Override
        public void remove(@NonNull String category, @NonNull String name) {}

        @Override
        public void addUpdater(@NonNull Runnable updater) {}

        @Override
        public void removeUpdater(@NonNull Runnable updater) {}

        @Override
        public void start() {}
    }

    /** Creates a minimal ConfigProvider */
    public static ConfigProvider createBenchmarkConfigProvider() {
        return new ConfigProvider() {
            @Override
            public @NonNull VersionedConfigImpl getConfiguration() {
                return new VersionedConfigImpl(createBenchmarkConfiguration(), 1L);
            }
        };
    }

    /** Creates a minimal Configuration with hardcoded values */
    private static Configuration createBenchmarkConfiguration() {
        SimpleConfigSource source = new SimpleConfigSource()
                .withValue("blockStream.streamMode", "BOTH")
                .withValue("blockStream.writerMode", "FILE")
                .withValue("blockStream.blockFileDir", "/tmp/benchmark-blocks")
                .withValue("blockStream.hashCombineBatchSize", "32")
                .withValue("blockStream.roundsPerBlock", "1")
                .withValue("blockStream.blockPeriod", "2s")
                .withValue("blockStream.receiptEntriesBatchSize", "8192")
                .withValue("blockStream.workerLoopSleepDuration", "10ms")
                .withValue("blockStream.maxConsecutiveScheduleSecondsToProbe", "100")
                .withValue("blockStream.quiescedHeartbeatInterval", "1s")
                .withValue("blockStream.maxReadDepth", "512")
                .withValue("blockStream.maxReadBytesSize", "500000000")
                .withValue("tss.hintsEnabled", "false")
                .withValue("tss.historyEnabled", "false")
                .withValue("quiescence.enabled", "false")
                .withValue("quiescence.tctDuration", "5s")
                .withValue("networkAdmin.diskNetworkExport", "NEVER")
                .withValue("networkAdmin.diskNetworkExportFile", "/tmp/benchmark-network-export")
                .withValue("version.hapiVersion", "0.56.0")
                .withValue("staking.periodMins", "1440")
                .withValue("blockRecordStream.numOfBlockHashesInState", "256");

        return ConfigurationBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(TssConfig.class)
                .withConfigDataType(QuiescenceConfig.class)
                .withConfigDataType(NetworkAdminConfig.class)
                .withConfigDataType(VersionConfig.class)
                .withConfigDataType(StakingConfig.class)
                .withConfigDataType(BlockRecordStreamConfig.class)
                .withConverter(HederaFunctionalitySet.class, new FunctionalitySetConverter())
                .withConverter(SemanticVersion.class, new SemanticVersionConverter())
                .withSource(source)
                .build();
    }
}
