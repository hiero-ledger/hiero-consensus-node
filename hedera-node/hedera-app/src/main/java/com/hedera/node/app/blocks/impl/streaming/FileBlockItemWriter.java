// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.swirlds.state.lifecycle.HapiUtils.asAccountString;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.schema.BlockSchema;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Writes serialized block items to files, one per block number.
 */
public class FileBlockItemWriter implements BlockItemWriter {

    private static final Logger logger = LogManager.getLogger(FileBlockItemWriter.class);

    /** The file extension for block files. */
    private static final String RECORD_EXTENSION = "blk";

    /** The suffix added to RECORD_EXTENSION when they are compressed. */
    private static final String COMPRESSION_ALGORITHM_EXTENSION = ".gz";

    /** Whether to compress the block files. */
    private final boolean compressFiles;

    /** The node-specific path to the directory where block files are written */
    private final Path nodeScopedBlockDir;

    /** The file output stream we are writing to, which writes to the configured block file path */
    private WritableStreamingData writableStreamingData;

    /** The state of this writer */
    private State state;

    /**
     * The block number for the file we are writing. Each file corresponds to one, and only one, block. Once it is
     * set in {@link #openBlock}, it is never changed.
     */
    private long blockNumber;

    private enum State {
        UNINITIALIZED,
        OPEN,
        CLOSED
    }

    /**
     * Construct a new FileBlockItemWriter.
     *
     * @param configProvider configuration provider
     * @param nodeInfo information about the current node
     * @param fileSystem the file system to use for writing block files
     */
    public FileBlockItemWriter(
            @NonNull final ConfigProvider configProvider,
            @NonNull final NodeInfo nodeInfo,
            @NonNull final FileSystem fileSystem) {
        requireNonNull(configProvider, "The supplied argument 'configProvider' cannot be null!");
        requireNonNull(nodeInfo, "The supplied argument 'nodeInfo' cannot be null!");
        requireNonNull(fileSystem, "The supplied argument 'fileSystem' cannot be null!");

        this.state = State.UNINITIALIZED;
        final var config = configProvider.getConfiguration();
        final var blockStreamConfig = config.getConfigData(BlockStreamConfig.class);
        this.compressFiles = blockStreamConfig.compressFilesOnCreation();

        // Compute directory for block files
        final Path blockDir = fileSystem.getPath(blockStreamConfig.blockFileDir());
        nodeScopedBlockDir = blockDir.resolve("block-" + asAccountString(nodeInfo.accountId()));
    }

    @Override
    public void openBlock(long blockNumber) {
        if (state == State.OPEN) throw new IllegalStateException("Cannot initialize a FileBlockItemWriter twice");
        if (blockNumber < 0) throw new IllegalArgumentException("Block number must be non-negative");

        this.blockNumber = blockNumber;
        final var blockFilePath = getBlockFilePath(blockNumber);
        OutputStream out = null;
        try {
            if (!Files.exists(nodeScopedBlockDir)) {
                Files.createDirectories(nodeScopedBlockDir);
            }
            out = Files.newOutputStream(blockFilePath);
            out = new BufferedOutputStream(out, 1024 * 1024); // 1 MB
            if (compressFiles) {
                out = new GZIPOutputStream(out, 1024 * 256); // 256 KB
                // By wrapping the GZIPOutputStream in a BufferedOutputStream, the code reduces the number of write
                // operations to the GZIPOutputStream, and therefore the number of synchronized calls. Instead of
                // writing each small piece of data immediately to the GZIPOutputStream, it writes the data to the
                // buffer, and only when the buffer is full, it writes all the data to the GZIPOutputStream in one go.
                // This can significantly improve the performance when writing many small amounts of data.
                out = new BufferedOutputStream(out, 1024 * 1024 * 4); // 4 MB
            }

            this.writableStreamingData = new WritableStreamingData(out);
        } catch (final IOException e) {
            // If an exception was thrown, we should close the stream if it was opened to prevent a resource leak.
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ex) {
                    logger.error("Error closing the FileBlockItemWriter output stream", ex);
                }
            }
            // We must be able to produce blocks.
            logger.fatal("Could not create block file {}", blockFilePath, e);
            throw new UncheckedIOException(e);
        }

        state = State.OPEN;
        if (logger.isDebugEnabled()) {
            logger.debug("Started new block in FileBlockItemWriter {}", blockNumber);
        }
    }

    @Override
    public void writeItem(@NonNull final byte[] bytes) {
        requireNonNull(bytes);
        if (state != State.OPEN) {
            throw new IllegalStateException(
                    "Cannot write to a FileBlockItemWriter that is not open for block: " + this.blockNumber);
        }

        // Write the ITEMS tag.
        ProtoWriterTools.writeTag(writableStreamingData, BlockSchema.ITEMS, ProtoConstants.WIRE_TYPE_DELIMITED);
        // Write the length of the item.
        writableStreamingData.writeVarInt(bytes.length, false);
        // Write the item bytes themselves.
        writableStreamingData.writeBytes(bytes);
    }

    @Override
    public void writePbjItem(@NonNull BlockItem item) {
        throw new UnsupportedOperationException("writePbjItem is not supported in this implementation");
    }

    @Override
    public void closeBlock() {
        if (state.ordinal() < State.OPEN.ordinal()) {
            throw new IllegalStateException("Cannot close a FileBlockItemWriter that is not open");
        } else if (state.ordinal() == State.CLOSED.ordinal()) {
            throw new IllegalStateException("Cannot close a FileBlockItemWriter that is already closed");
        }

        // Close the writableStreamingData.
        try {
            writableStreamingData.close();
            state = State.CLOSED;
            if (logger.isDebugEnabled()) {
                logger.debug("Closed block in FileBlockItemWriter {}", blockNumber);
            }

            // Write a .mf file to indicate that the block file is complete.
            final Path markerFile = getBlockFilePath(blockNumber).resolveSibling(longToFileName(blockNumber) + ".mf");
            if (Files.exists(markerFile)) {
                logger.info("Skipping block marker file for {} as it already exists", markerFile);
            } else {
                Files.createFile(markerFile);
            }
        } catch (final IOException e) {
            logger.error("Error closing the FileBlockItemWriter output stream", e);
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Get the path for a block file with the block number.
     *
     * @param blockNumber the block number for the block file
     * @return Path to a block file for that block number
     */
    @NonNull
    private Path getBlockFilePath(final long blockNumber) {
        return nodeScopedBlockDir.resolve(longToFileName(blockNumber) + "." + RECORD_EXTENSION
                + (compressFiles ? COMPRESSION_ALGORITHM_EXTENSION : ""));
    }

    /**
     * Convert a long to a 36-character string, padded with leading zeros.
     * @param value the long to convert
     * @return the 36-character string padded with leading zeros
     */
    @NonNull
    public static String longToFileName(final long value) {
        // Convert the signed long to an unsigned long using BigInteger for correct representation
        BigInteger unsignedValue =
                BigInteger.valueOf(value & Long.MAX_VALUE).add(BigInteger.valueOf(Long.MIN_VALUE & value));

        // Format the unsignedValue as a 36-character string, padded with leading zeros to ensure we have enough digits
        // for an unsigned long. However, to allow for future expansion, we use 36 characters as that's what UUID uses.
        return String.format("%036d", unsignedValue);
    }
}
