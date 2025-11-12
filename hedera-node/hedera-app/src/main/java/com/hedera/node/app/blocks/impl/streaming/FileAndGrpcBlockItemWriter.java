// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.internal.network.PendingProof;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.FileSystem;

/**
 * Writes serialized block items to files and streams bidirectionally for the publishBlockStream rpc in BlockStreamService.
 */
public class FileAndGrpcBlockItemWriter implements BlockItemWriter {
    private final FileBlockItemWriter fileBlockItemWriter;
    private final GrpcBlockItemWriter grpcBlockItemWriter;
    private final ConfigProvider configProvider;

    /**
     * Construct a new FileAndGrpcBlockItemWriter.
     *
     * @param configProvider configuration provider
     * @param nodeInfo information about the current node
     * @param fileSystem the file system to use for writing block files
     * @param blockBufferService the block stream state manager
     */
    public FileAndGrpcBlockItemWriter(
            @NonNull final ConfigProvider configProvider,
            @NonNull final NodeInfo nodeInfo,
            @NonNull final FileSystem fileSystem,
            @NonNull final BlockBufferService blockBufferService) {
        this.fileBlockItemWriter = new FileBlockItemWriter(configProvider, nodeInfo, fileSystem);
        this.grpcBlockItemWriter = new GrpcBlockItemWriter(blockBufferService);
        this.configProvider = requireNonNull(configProvider, "configProvider must not be null");
    }

    private boolean isStreamingEnabled() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .streamToBlockNodes();
    }

    @Override
    public void openBlock(final long blockNumber) {
        this.fileBlockItemWriter.openBlock(blockNumber);
        if (isStreamingEnabled()) {
            this.grpcBlockItemWriter.openBlock(blockNumber);
        }
    }

    @Override
    public void closeCompleteBlock() {
        this.fileBlockItemWriter.closeCompleteBlock();
        if (isStreamingEnabled()) {
            this.grpcBlockItemWriter.closeCompleteBlock();
        }
    }

    @Override
    public void flushPendingBlock(@NonNull final PendingProof pendingProof) {
        requireNonNull(pendingProof);
        this.fileBlockItemWriter.flushPendingBlock(pendingProof);
        if (isStreamingEnabled()) {
            this.grpcBlockItemWriter.flushPendingBlock(pendingProof);
        }
    }

    @Override
    public void writePbjItem(@NonNull final BlockItem item) {
        this.fileBlockItemWriter.writePbjItem(item);
        if (isStreamingEnabled()) {
            this.grpcBlockItemWriter.writePbjItem(item);
        }
    }
}
