// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.info.NodeInfo;
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
     * @param blockNodeConnectionManager the block node connection manager
     */
    public FileAndGrpcBlockItemWriter(
            @NonNull final ConfigProvider configProvider,
            @NonNull final NodeInfo nodeInfo,
            @NonNull final FileSystem fileSystem,
            @NonNull final BlockNodeConnectionManager blockNodeConnectionManager,
            @NonNull final BlockStreamStateManager blockStreamStateManager) {
        this.fileBlockItemWriter = new FileBlockItemWriter(configProvider, nodeInfo, fileSystem);
        this.grpcBlockItemWriter = new GrpcBlockItemWriter(blockNodeConnectionManager, blockStreamStateManager);
    }

    @Override
    public void openBlock(long blockNumber) {
        this.fileBlockItemWriter.openBlock(blockNumber);
        this.grpcBlockItemWriter.openBlock(blockNumber);
    }

    @Override
    public void writePbjItem(@NonNull Bytes bytes) {
        this.fileBlockItemWriter.writeItem(bytes.toByteArray());
        this.grpcBlockItemWriter.writePbjItem(bytes);
    }

    @Override
    public void writeItem(@NonNull byte[] bytes) {
        this.fileBlockItemWriter.writeItem(bytes);
        // The GrpcBlockItemWriter doesn't support writeItem, so we don't call it here
    }

    @Override
    public void closeBlock() {
        this.fileBlockItemWriter.closeBlock();
        this.grpcBlockItemWriter.closeBlock();
    }

    @Override
    public void performPreBlockProofActions() {
        // The FileBlockItemWriter doesn't support performPreBlockProofActions, so we don't call it here
        this.grpcBlockItemWriter.performPreBlockProofActions();
    }
}
