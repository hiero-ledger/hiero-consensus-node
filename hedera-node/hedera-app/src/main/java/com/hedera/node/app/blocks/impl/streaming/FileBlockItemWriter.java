// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.hapi.util.HapiUtils.asAccountString;
import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.block.stream.schema.BlockSchema;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.internal.network.PendingProof;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.ToLongFunction;
import java.util.function.UnaryOperator;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Writes serialized block items to files, one per block number.
 */
public class FileBlockItemWriter implements BlockItemWriter {

    private static final Logger logger = LogManager.getLogger(FileBlockItemWriter.class);

    private static final ToLongFunction<File> PROOF_JSON_BLOCK_NUMBER_FN =
            f -> Long.parseLong(f.getName().substring(0, f.getName().length() - ".pnd.json".length()));
    private static final Comparator<File> PROOF_JSON_FILE_COMPARATOR =
            Comparator.comparingLong(PROOF_JSON_BLOCK_NUMBER_FN);

    /** The file extension for complete block files. */
    private static final String COMPLETE_BLOCK_EXTENSION = ".blk";

    /** The suffix added to RECORD_EXTENSION when they are compressed. */
    private static final String COMPRESSION_ALGORITHM_EXTENSION = ".gz";

    /** The node-specific path to the directory where block files are written */
    private final Path nodeScopedBlockDir;

    /**
     * Converts a base block number file name to the name of a complete block file.
     */
    private final UnaryOperator<String> completeFileName;

    /**
     * Converts a base block number file name to the name of a pending block file.
     */
    private final UnaryOperator<String> pendingFileName;

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

        // Compute directory for block files
        final Path blockDir = fileSystem.getPath(blockStreamConfig.blockFileDir());
        nodeScopedBlockDir = blockDir.resolve("block-" + asAccountString(nodeInfo.accountId()));

        this.completeFileName = name -> name + COMPLETE_BLOCK_EXTENSION + COMPRESSION_ALGORITHM_EXTENSION;
        this.pendingFileName = name -> name + ".pnd" + COMPRESSION_ALGORITHM_EXTENSION;
    }

    /**
     * The on-disk contents of a pending block that could not get a signature before the network froze.
     * @param items the items in the block
     * @param pendingProof the pending proof for the block
     */
    public record OnDiskPendingBlock(
            @NonNull List<BlockItem> items,
            @NonNull PendingProof pendingProof,
            @NonNull Path proofJsonPath,
            @NonNull Path contentsPath) {
        public OnDiskPendingBlock {
            requireNonNull(items);
            requireNonNull(pendingProof);
            requireNonNull(proofJsonPath);
            requireNonNull(contentsPath);
        }

        /**
         * The number of the block.
         */
        public long number() {
            return pendingProof.block();
        }

        /**
         * The hash of the block.
         */
        public Bytes blockHash() {
            return pendingProof.blockHash();
        }

        /**
         * The builder to resume work on the block's proof.
         */
        public BlockProof.Builder proofBuilder() {
            return BlockProof.newBuilder()
                    .block(pendingProof().block())
                    .previousBlockRootHash(pendingProof.previousBlockHash())
                    .startOfBlockStateRootHash(pendingProof.startOfBlockStateRootHash());
        }

        /**
         * If there was any block with an unknown signature before this one, the hashes of the siblings of the
         * previous block's root in its path to this block's root.
         */
        public MerkleSiblingHash[] siblingHashesIfUseful() {
            return pendingProof.siblingHashesFromPrevBlockRoot().toArray(new MerkleSiblingHash[0]);
        }
    }

    /**
     * Get the expected block directory for the given node.
     * @param config the configuration
     * @return the expected block directory
     */
    public static Path blockDirFor(@NonNull final Configuration config) {
        requireNonNull(config);
        return getAbsolutePath(config.getConfigData(BlockStreamConfig.class).blockFileDir());
    }

    /**
     * Loads pending blocks from the given directory, identifying them by the presence of {@code .pnd.json} files
     * with pending block proofs. The contents of the blocks are read from the corresponding {@code .pnd.gz} or
     * {@code .pnd} files.
     * @param blockDirPath the directory containing subdirectories to load pending blocks from
     * @param followingBlockNumber the block number the pending blocks should be immediately preceding
     * @return the list of pending blocks
     */
    public static List<OnDiskPendingBlock> loadContiguousPendingBlocks(
            @NonNull final Path blockDirPath, final long followingBlockNumber) {
        requireNonNull(blockDirPath);
        final List<OnDiskPendingBlock> pendingBlocks = new LinkedList<>();
        final File[] pendingBlocksPaths = blockDirPath.toFile().listFiles();
        if (pendingBlocksPaths == null) {
            logger.warn("No subdirectories found in {}", blockDirPath);
            return pendingBlocks;
        }

        final var proofJsons = new ArrayList<File>();
        for (final var pendingBlocksPath : pendingBlocksPaths) {
            final File[] pendingJsons =
                    requireNonNull(pendingBlocksPath.listFiles((dir, name) -> name.endsWith(".pnd.json")));
            proofJsons.addAll(Arrays.asList(pendingJsons));
        }

        if (proofJsons.isEmpty()) {
            logger.warn("No pending blocks found in any subdirectories of {}", blockDirPath);
            return pendingBlocks;
        }

        proofJsons.sort(PROOF_JSON_FILE_COMPARATOR.reversed());

        logger.info("Evaluating {} pending blocks on disk", proofJsons.size());
        long nextContiguousBlock = followingBlockNumber - 1;
        for (int i = 0; i < proofJsons.size(); i++) {
            final var proofJson = proofJsons.get(i);
            final long nextNumber = PROOF_JSON_BLOCK_NUMBER_FN.applyAsLong(proofJson);
            if (nextNumber != nextContiguousBlock) {
                logger.info("No more contiguous blocks (#{} != #{})", nextNumber, nextContiguousBlock);
                break;
            } else {
                logger.info("Trying to load next contiguous pending block #{}", nextNumber);
                nextContiguousBlock--;
            }
            final var proofJsonPath = proofJson.toPath();
            final PendingProof pendingProof;
            try {
                pendingProof = PendingProof.JSON.parse(new ReadableStreamingData(proofJsonPath));
            } catch (IOException | ParseException e) {
                logger.warn(
                        "Error reading pending proof metadata from {} (not considering remaining - {})",
                        proofJson.toPath(),
                        Arrays.toString(Arrays.copyOfRange(proofJsons.toArray(), i + 1, proofJsons.size())));
                break;
            }
            Block partialBlock = null;
            final var name = proofJson.getName();
            Path contentsPath = proofJson.toPath().resolveSibling(name.replace(".pnd.json", ".pnd.gz"));
            if (contentsPath.toFile().exists()) {
                try (final GZIPInputStream in = new GZIPInputStream(Files.newInputStream(contentsPath))) {
                    partialBlock = Block.PROTOBUF.parse(Bytes.wrap(in.readAllBytes()));
                } catch (IOException | ParseException e) {
                    logger.warn("Error reading zipped pending block contents from {}", contentsPath, e);
                }
            } else {
                contentsPath = proofJson.toPath().resolveSibling(name.replace(".pnd.json", ".pnd"));
                if (contentsPath.toFile().exists()) {
                    try {
                        partialBlock = Block.PROTOBUF.parse(Bytes.wrap(Files.readAllBytes(contentsPath)));
                    } catch (IOException | ParseException e) {
                        logger.warn("Error reading pending block contents from {}", contentsPath, e);
                    }
                }
            }
            if (partialBlock == null) {
                logger.warn(
                        "No pending block contents found for {} (not considering remaining - {})",
                        proofJson.toPath(),
                        Arrays.toString(Arrays.copyOfRange(proofJsons.toArray(), i + 1, proofJsons.size())));
                break;
            } else {
                pendingBlocks.addFirst(
                        new OnDiskPendingBlock(partialBlock.items(), pendingProof, proofJsonPath, contentsPath));
            }
        }
        return pendingBlocks;
    }

    /**
     * Cleans up the pending block at the given path by deleting the corresponding {@code .pnd.json} and block contents.
     * @param contentsPath the path to the block contents
     */
    public static void cleanUpPendingBlock(@NonNull final Path contentsPath) {
        requireNonNull(contentsPath);
        final var name = contentsPath.getFileName().toString();
        final var suffix = name.endsWith(".pnd.gz") ? ".pnd.gz" : ".pnd";
        final var proofJsonPath = contentsPath.resolveSibling(name.replace(suffix, ".pnd.json"));
        logger.info("Cleaning up pending block ({}, {})", proofJsonPath, contentsPath);
        if (!proofJsonPath.toFile().delete()) {
            logger.warn("Failed to delete pending proof metadata at {}", proofJsonPath);
        }
        if (!contentsPath.toFile().delete()) {
            logger.warn("Failed to delete pending block contents at {}", contentsPath);
        }
    }

    @Override
    public void openBlock(final long blockNumber) {
        if (state == State.OPEN) throw new IllegalStateException("Cannot initialize a FileBlockItemWriter twice");
        if (blockNumber < 0) throw new IllegalArgumentException("Block number must be non-negative");

        this.blockNumber = blockNumber;
        final var blockFilePath = pathOf(blockNumber, completeFileName);
        OutputStream out = null;
        try {
            if (!Files.exists(nodeScopedBlockDir)) {
                Files.createDirectories(nodeScopedBlockDir);
            }
            out = Files.newOutputStream(blockFilePath);
            out = new BufferedOutputStream(out, 1024 * 1024); // 1 MB
            out = new GZIPOutputStream(out, 1024 * 256); // 256 KB
            // By wrapping the GZIPOutputStream in a BufferedOutputStream, the code reduces the number of write
            // operations to the GZIPOutputStream, and therefore the number of synchronized calls. Instead of
            // writing each small piece of data immediately to the GZIPOutputStream, it writes the data to the
            // buffer, and only when the buffer is full, it writes all the data to the GZIPOutputStream in one go.
            // This can significantly improve the performance when writing many small amounts of data.
            out = new BufferedOutputStream(out, 1024 * 1024 * 4); // 4 MB

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

    /**
     * Writes a serialized item to the destination stream.
     *
     * @param bytes the serialized item to write
     */
    void writeItem(@NonNull final byte[] bytes) {
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

    /**
     * Writes a block item and its serialized bytes to the destination stream.
     * Only the serialized bytes are used, the block item is ignored.
     *
     * @param item the item to write (ignored in this implementation)
     * @param bytes the serialized item to write
     */
    @Override
    public void writePbjItemAndBytes(@NonNull final BlockItem item, @NonNull final Bytes bytes) {
        requireNonNull(bytes, "bytes must not be null");
        writeItem(bytes.toByteArray());
    }

    @Override
    public void writePbjItem(@NonNull BlockItem item) {
        throw new UnsupportedOperationException("writePbjItem is not supported in this implementation");
    }

    @Override
    public void closeCompleteBlock() {
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
            final Path markerFile = pathOf(blockNumber, name -> name + ".mf");
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

    @Override
    public void flushPendingBlock(@NonNull final PendingProof pendingProof) {
        requireNonNull(pendingProof);
        if (state == State.OPEN) {
            try {
                writableStreamingData.close();
                writableStreamingData.flush();
                Files.move(pathOf(blockNumber, completeFileName), pathOf(blockNumber, pendingFileName));
            } catch (IOException e) {
                logger.error("Error flushing pending block #{}", blockNumber, e);
                return;
            } finally {
                state = State.CLOSED;
            }
            final var json = PendingProof.JSON.toJSON(pendingProof);
            try {
                Files.writeString(pathOf(blockNumber, name -> name + ".pnd.json"), json);
            } catch (IOException e) {
                logger.error("Error flushing pending proof metadata #{}", blockNumber, e);
            }
            logger.info(
                    "Flushed pending block #{} ({}, {})",
                    blockNumber,
                    pathOf(blockNumber, pendingFileName),
                    pathOf(blockNumber, name -> name + ".pnd.json"));
        } else {
            logger.warn("Block #{} flushed in non-OPEN state '{}'", blockNumber, state, new IllegalStateException());
        }
    }

    @Override
    public void jumpToBlockAfterFreeze(long blockNumber) {
        // no-op
    }

    /**
     * Get the path for a block file with the block number.
     *
     * @param blockNumber the block number for the block file
     * @return Path to a block file for that block number
     */
    @NonNull
    private Path pathOf(final long blockNumber, @NonNull final UnaryOperator<String> nameFn) {
        return nodeScopedBlockDir.resolve(nameFn.apply(longToFileName(blockNumber)));
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
