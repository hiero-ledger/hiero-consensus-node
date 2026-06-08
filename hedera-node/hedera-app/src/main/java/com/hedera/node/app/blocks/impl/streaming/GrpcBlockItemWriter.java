// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.hapi.util.HapiUtils.asAccountString;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.internal.network.PendingProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.zip.GZIPOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A block item writer implementation that streams blocks to Block Nodes using gRPC bidirectional streaming.
 * This writer interfaces with {@link BlockBufferService} to manage block states and coordinates
 * the streaming of block items.
 * @see BlockBufferService
 * @see BlockNodeStreamingConnection
 */
public class GrpcBlockItemWriter implements BlockItemWriter {
    private static final Logger logger = LogManager.getLogger(GrpcBlockItemWriter.class);
    private static final String COMPLETE_PENDING_EXTENSION = ".pnd.gz";
    private final BlockBufferService blockBufferService;
    private final ConfigProvider configProvider;
    private final SelfNodeAccountIdManager selfNodeAccountIdManager;
    private final FileSystem fileSystem;
    private long blockNumber;
    private final Path nodeScopedBlockDir;

    /**
     * Construct a new GrpcBlockItemWriter.
     *
     * @param configProvider configuration provider
     * @param selfNodeAccountIdManager information about the current node
     * @param fileSystem the file system to use for writing block files
     * @param blockBufferService the block stream state manager that maintains the state of the block
     */
    public GrpcBlockItemWriter(
            @NonNull final ConfigProvider configProvider,
            @NonNull final SelfNodeAccountIdManager selfNodeAccountIdManager,
            @NonNull final FileSystem fileSystem,
            @NonNull final BlockBufferService blockBufferService) {
        this.configProvider = requireNonNull(configProvider, "configProvider must not be null");
        this.selfNodeAccountIdManager =
                requireNonNull(selfNodeAccountIdManager, "selfNodeAccountIdManager must not be null");
        this.fileSystem = requireNonNull(fileSystem, "fileSystem must not be null");
        this.blockBufferService = requireNonNull(blockBufferService, "blockBufferService must not be null");
        // Compute directory for block files
        final var blockStreamConfig = configProvider.getConfiguration().getConfigData(BlockStreamConfig.class);
        final Path blockDir = fileSystem.getPath(blockStreamConfig.blockFileDir());
        nodeScopedBlockDir =
                blockDir.resolve("block-" + asAccountString(selfNodeAccountIdManager.getSelfNodeAccountId()));
    }

    /**
     * Opens a new block for writing with the specified block number. This initializes the block state
     * in the state manager and prepares for receiving block items.
     *
     * @param blockNumber the sequence number of the block to open
     */
    @Override
    public void openBlock(long blockNumber) {
        if (blockNumber < 0) throw new IllegalArgumentException("Block number must be non-negative");
        this.blockNumber = blockNumber;
        blockBufferService.openBlock(blockNumber);
        logger.debug("Started new block in GrpcBlockItemWriter {}", blockNumber);
    }

    /**
     * Writes a protocol buffer formatted block item to the current block's state.
     *
     * @param blockItem the block item to write
     */
    @Override
    public void writePbjItem(@NonNull BlockItem blockItem) {
        requireNonNull(blockItem, "blockItem must not be null");
        blockBufferService.addItem(blockNumber, blockItem);
    }

    /**
     * Writes a protocol buffer formatted block item and its serialized bytes to the current block's state.
     * Only the block item is used, the serialized bytes are ignored.
     *
     * @param item the block item to write
     * @param bytes the serialized item to write (ignored in this implementation)
     */
    @Override
    public void writePbjItemAndBytes(@NonNull final BlockItem item, @NonNull final Bytes bytes) {
        requireNonNull(item, "item must not be null");
        requireNonNull(bytes, "bytes must not be null");
        writePbjItem(item);
    }

    /**
     * Closes the current block and marks it as complete in the state manager.
     */
    @Override
    public void closeCompleteBlock() {
        blockBufferService.closeBlock(blockNumber);
        logger.debug("Closed block in GrpcBlockItemWriter {}", blockNumber);
    }

    @Override
    public void flushPendingBlock(@NonNull final PendingProof pendingProof) {
        requireNonNull(pendingProof, "pendingProof must not be null");
        final var blockState = blockBufferService.getBlockState(blockNumber);
        if (blockState == null) {
            logger.warn("Cannot flush pending block #{} because no block state is available", blockNumber);
            return;
        }
        final var items = new ArrayList<BlockItem>(blockState.itemCount());
        for (int i = 0; i < blockState.itemCount(); i++) {
            final var item = blockState.blockItem(i);
            if (item != null) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            logger.warn("Cannot flush pending block #{} because no block items are available", blockNumber);
            return;
        }
        try {
            Files.createDirectories(nodeScopedBlockDir);
            final var contentsPath = pendingContentsPath(nodeScopedBlockDir, blockNumber);
            try (final var out = new GZIPOutputStream(Files.newOutputStream(contentsPath))) {
                out.write(Block.PROTOBUF.toBytes(new Block(items)).toByteArray());
            }
            final var proofPath = pendingProofPath(nodeScopedBlockDir, blockNumber);
            Files.writeString(proofPath, PendingProof.JSON.toJSON(pendingProof));
            logger.info("Flushed pending block #{} ({}, {})", blockNumber, contentsPath, proofPath);
        } catch (IOException e) {
            logger.error("Error flushing pending block #{}", blockNumber, e);
        }
    }

    private Path pendingContentsPath(@NonNull final Path blockDir, final long blockNumber) {
        final var baseName = FileBlockItemWriter.longToFileName(blockNumber);
        return blockDir.resolve(baseName + COMPLETE_PENDING_EXTENSION);
    }
}
