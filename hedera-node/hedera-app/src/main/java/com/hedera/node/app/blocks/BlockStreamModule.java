// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.node.app.blocks.impl.BlockStreamManagerImpl;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.blocks.impl.streaming.FileAndGrpcBlockItemWriter;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.node.app.blocks.impl.streaming.GrpcBlockItemWriter;
import com.hedera.node.app.services.NodeRewardManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.info.NodeInfo;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.FileSystem;
import java.util.function.Supplier;
import javax.inject.Singleton;

@Module
public interface BlockStreamModule {

    @Provides
    @Singleton
    static BlockNodeConnectionManager provideBlockNodeConnectionManager(ConfigProvider configProvider) {
        return new BlockNodeConnectionManager(configProvider);
    }

    @Provides
    @Singleton
    static BlockStreamManager provideBlockStreamManager(BlockStreamManagerImpl impl) {
        return impl;
    }

    @Provides
    @Singleton
    static Supplier<BlockItemWriter> bindBlockItemWriterSupplier(
            @NonNull final ConfigProvider configProvider,
            @NonNull final NodeInfo selfNodeInfo,
            @NonNull final FileSystem fileSystem,
            @NonNull final BlockNodeConnectionManager blockNodeConnectionManager) {
        final var config = configProvider.getConfiguration();
        final var blockStreamConfig = config.getConfigData(BlockStreamConfig.class);
        return switch (blockStreamConfig.writerMode()) {
            case FILE -> () -> new FileBlockItemWriter(configProvider, selfNodeInfo, fileSystem);
            case GRPC -> () -> new GrpcBlockItemWriter(blockNodeConnectionManager);
            case FILE_AND_GRPC -> () -> new FileAndGrpcBlockItemWriter(
                    configProvider, selfNodeInfo, fileSystem, blockNodeConnectionManager);
        };
    }

    @Provides
    @Singleton
    static BlockStreamManager.Lifecycle provideBlockStreamManagerLifecycle(
            @NonNull final NodeRewardManager nodeRewardManager, @NonNull final BoundaryStateChangeListener listener) {
        return new BlockStreamManager.Lifecycle() {
            @Override
            public void onOpenBlock(@NonNull final State state) {
                listener.resetCollectedNodeFees();
                nodeRewardManager.onOpenBlock(state);
            }

            @Override
            public void onCloseBlock(@NonNull final State state) {
                nodeRewardManager.onCloseBlock(state, listener.nodeFeesCollected());
            }
        };
    }
}
