// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.internal.network.PendingProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.FileSystem;

/**
 * Writes serialized block items to files and streams bidirectionally for the publishBlockStream rpc in BlockStreamService.
 */
public class FileAndGrpcBlockItemWriter implements BlockItemWriter {
    private final FileBlockItemWriter fileBlockItemWriter;
    private final GrpcBlockItemWriter grpcBlockItemWriter;

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
    }

    @Override
    public void openBlock(long blockNumber) {
        this.fileBlockItemWriter.openBlock(blockNumber);
        this.grpcBlockItemWriter.openBlock(blockNumber);
    }

    @Override
    public void writePbjItemAndBytes(@NonNull final BlockItem item, @NonNull Bytes bytes) {
        this.fileBlockItemWriter.writeItem(bytes.toByteArray());
        this.grpcBlockItemWriter.writePbjItem(item);
    }

    @Override
    public void writeItem(@NonNull byte[] bytes) {
        this.fileBlockItemWriter.writeItem(bytes);
        // The GrpcBlockItemWriter doesn't support writeItem, so we don't call it here
    }

    @Override
    public void closeCompleteBlock() {
        this.fileBlockItemWriter.closeCompleteBlock();
        this.grpcBlockItemWriter.closeCompleteBlock();
    }

    @Override
    public void flushPendingBlock(@NonNull final PendingProof pendingProof) {
        requireNonNull(pendingProof);
        this.fileBlockItemWriter.flushPendingBlock(pendingProof);
        this.grpcBlockItemWriter.flushPendingBlock(pendingProof);
    }

    @Override
    public void writePbjItem(@NonNull BlockItem item) {
        throw new UnsupportedOperationException("writePbjItem is not supported in this implementation");
    }
}
