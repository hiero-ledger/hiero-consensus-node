// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.node.app.blocks.impl.BlockStreamManagerImpl;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.blocks.impl.streaming.FileAndGrpcBlockItemWriter;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.node.app.blocks.impl.streaming.GrpcBlockItemWriter;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.services.NodeFeeManager;
import com.hedera.node.app.services.NodeRewardManager;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.State;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.FileSystem;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javax.inject.Named;
import javax.inject.Singleton;

@Module
public interface BlockStreamModule {

    @Provides
    @Singleton
    static BlockBufferService provideBlockBufferService(
            @NonNull final ConfigProvider configProvider, @NonNull final BlockStreamMetrics blockStreamMetrics) {
        return new BlockBufferService(configProvider, blockStreamMetrics);
    }

    @Provides
    @Singleton
    static BlockNodeConnectionManager provideBlockNodeConnectionManager(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockBufferService blockBufferService,
            @NonNull final BlockStreamMetrics blockStreamMetrics,
            @NonNull @Named("bn-blockingio-exec") final Supplier<ExecutorService> blockingIoExecutorSupplier) {
        final BlockNodeConnectionManager manager = new BlockNodeConnectionManager(
                configProvider, blockBufferService, blockStreamMetrics, blockingIoExecutorSupplier);
        blockBufferService.setBlockNodeConnectionManager(manager);
        manager.start();
        return manager;
    }

    @Provides
    @Named("bn-blockingio-exec")
    static Supplier<ExecutorService> provideBlockingIoExecutorSupplier() {
        return Executors::newVirtualThreadPerTaskExecutor;
    }

    @Provides
    @Singleton
    static BlockStreamMetrics provideBlockStreamMetrics(@NonNull final Metrics metrics) {
        return new BlockStreamMetrics(metrics);
    }

    @Provides
    @Singleton
    static BlockStreamManager provideBlockStreamManager(@NonNull final BlockStreamManagerImpl impl) {
        return impl;
    }

    @Provides
    @Singleton
    static Supplier<BlockItemWriter> bindBlockItemWriterSupplier(
            @NonNull final ConfigProvider configProvider,
            @NonNull final SelfNodeAccountIdManager selfNodeAccountIdManager,
            @NonNull final FileSystem fileSystem,
            @NonNull final BlockBufferService blockBufferService,
            @NonNull final BlockNodeConnectionManager blockNodeConnectionManager) {
        final var config = configProvider.getConfiguration();
        final var blockStreamConfig = config.getConfigData(BlockStreamConfig.class);

        return switch (blockStreamConfig.writerMode()) {
            case FILE -> () -> new FileBlockItemWriter(configProvider, selfNodeAccountIdManager, fileSystem);
            case GRPC -> () -> new GrpcBlockItemWriter(blockBufferService, blockNodeConnectionManager);
            case FILE_AND_GRPC ->
                () -> new FileAndGrpcBlockItemWriter(
                        configProvider,
                        selfNodeAccountIdManager,
                        fileSystem,
                        blockBufferService,
                        blockNodeConnectionManager);
        };
    }

    @Provides
    @Singleton
    static BlockStreamManager.Lifecycle provideBlockStreamManagerLifecycle(
            @NonNull final NodeRewardManager nodeRewardManager,
            @NonNull final BoundaryStateChangeListener listener,
            @NonNull final NodeFeeManager nodeFeeManager) {
        return new BlockStreamManager.Lifecycle() {
            @Override
            public void onOpenBlock(@NonNull final State state) {
                nodeFeeManager.onOpenBlock(state);
                listener.resetCollectedNodeFees();
                nodeRewardManager.onOpenBlock(state);
            }

            @Override
            public void onCloseBlock(@NonNull final State state) {
                nodeFeeManager.onCloseBlock(state);
                nodeRewardManager.onCloseBlock(state, listener.nodeFeesCollected());
            }
        };
    }
}
