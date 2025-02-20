// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.node.app.blocks.impl.BlockStreamManagerImpl;
import com.hedera.node.app.blocks.impl.FileBlockItemWriter;
import com.hedera.node.app.blocks.impl.GrpcBlockItemWriter;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.swirlds.state.lifecycle.info.NodeInfo;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.FileSystem;
import java.util.function.Supplier;
import javax.inject.Singleton;

@Module
public interface BlockStreamModule {
    @Binds
    @Singleton
    BlockStreamManager bindBlockStreamManager(BlockStreamManagerImpl blockStreamManager);

    @Provides
    @Singleton
    static Supplier<BlockItemWriter> bindBlockItemWriterSupplier(
            @NonNull final ConfigProvider configProvider,
            @NonNull final NodeInfo selfNodeInfo,
            @NonNull final FileSystem fileSystem) {
        final var config = configProvider.getConfiguration();
        final var blockStreamConfig = config.getConfigData(BlockStreamConfig.class);
        return switch (blockStreamConfig.writerMode()) {
            case FILE -> () -> new FileBlockItemWriter(configProvider, selfNodeInfo, fileSystem);
            case GRPC -> () -> new GrpcBlockItemWriter(blockStreamConfig);
        };
    }
    ;
}
