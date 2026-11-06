// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.hapi.util.HapiUtils.asAccountString;
import static com.hedera.node.app.blocks.BlockStreamManager.NUM_SIBLINGS_PER_BLOCK;
import static java.util.Objects.requireNonNull;
import static java.util.function.BinaryOperator.maxBy;
import static org.hiero.base.file.FileUtils.getAbsolutePath;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.block.stream.schema.BlockSchema;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.app.hapi.utils.blocks.BlockFileUtils;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
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
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Writes serialized block items to files, one per block number.
 */
public class FileBlockItemWriter implements BlockItemWriter {

    private static final Logger logger = LogManager.getLogger(FileBlockItemWriter.class);

    // Equal timestamps are broken by path so selection never depends on directory traversal order.
    private static final Comparator<PendingProofFile> PROOF_JSON_RECENCY_COMPARATOR = Comparator.comparing(
                    PendingProofFile::modifiedTime)
            .thenComparing(proof -> proof.path().toString());

    private record PendingProofFile(Path path, FileTime modifiedTime) {}

    /**
     * Number of bytes in a single kilobyte.
     */
    private static final int ONE_KB_BYTES = 1024;

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

    /**
     * Buffer size to use for the outer file writer - in bytes.
     */
    private final int blockFileBufferOuterSizeBytes;

    /**
     * Buffer size to use for the inner file writer - in bytes.
     */
    private final int blockFileBufferInnerSizeBytes;

    private enum State {
        UNINITIALIZED,
        OPEN,
        CLOSED
    }

    /**
     * Construct a new FileBlockItemWriter.
     *
     * @param configProvider configuration provider
     * @param selfNodeAccountIdManager information about the current node
     * @param fileSystem the file system to use for writing block files
     */
    public FileBlockItemWriter(
            @NonNull final ConfigProvider configProvider,
            @NonNull final SelfNodeAccountIdManager selfNodeAccountIdManager,
            @NonNull final FileSystem fileSystem) {
        requireNonNull(configProvider, "The supplied argument 'configProvider' cannot be null!");
        requireNonNull(selfNodeAccountIdManager, "The supplied argument 'nodeInfo' cannot be null!");
        requireNonNull(fileSystem, "The supplied argument 'fileSystem' cannot be null!");

        this.state = State.UNINITIALIZED;
        final var config = configProvider.getConfiguration();
        final var blockStreamConfig = config.getConfigData(BlockStreamConfig.class);

        blockFileBufferOuterSizeBytes = ONE_KB_BYTES * blockStreamConfig.blockFileBufferOuterSizeKb();
        blockFileBufferInnerSizeBytes = ONE_KB_BYTES * blockStreamConfig.blockFileBufferInnerSizeKb();

        // Compute directory for block files
        final Path blockDir = fileSystem.getPath(blockStreamConfig.blockFileDir());
        nodeScopedBlockDir =
                blockDir.resolve("block-" + asAccountString(selfNodeAccountIdManager.getSelfNodeAccountId()));
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
            return BlockProof.newBuilder().block(pendingProof().block());
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
     * with pending block proofs. The contents are read from the corresponding {@code .pnd.zstd},
     * legacy {@code .pnd.gz}, or plain {@code .pnd} file. Duplicate block numbers use the sidecar with the newest
     * modification time before continuity is checked. Invalid selected metadata or contents stop recovery; older
     * duplicates are not used as a fallback. Proofs at or after {@code followingBlockNumber} are ignored.
     * @param blockDirPath the directory containing subdirectories to load pending blocks from
     * @param followingBlockNumber the block number the pending blocks should be immediately preceding
     * @param maxReadDepth the max allowed depth of nested protobuf messages
     * @param maxReadSize the max size of protobuf messages to read
     * @return the list of pending blocks
     */
    public static List<OnDiskPendingBlock> loadContiguousPendingBlocks(
            @NonNull final Path blockDirPath,
            final long followingBlockNumber,
            final int maxReadDepth,
            final int maxReadSize) {
        requireNonNull(blockDirPath);
        final List<OnDiskPendingBlock> pendingBlocks = new LinkedList<>();
        final var proofJsons = new TreeMap<Long, PendingProofFile>();
        final File[] blockDirs = blockDirPath.toFile().listFiles(File::isDirectory);
        if (blockDirs == null) {
            logger.warn("No subdirectories found in {}", blockDirPath);
            return pendingBlocks;
        }
        try {
            for (final var blockDir : blockDirs) {
                for (final var proofJson :
                        requireNonNull(blockDir.listFiles((dir, name) -> name.endsWith(".pnd.json")))) {
                    final var name = proofJson.getName();
                    final long number;
                    try {
                        number = Long.parseLong(name.substring(0, name.length() - ".pnd.json".length()));
                    } catch (NumberFormatException e) {
                        logger.warn("Ignoring pending proof with invalid block number: {}", proofJson);
                        continue;
                    }
                    final var path = proofJson.toPath();
                    proofJsons.merge(
                            number,
                            new PendingProofFile(path, Files.getLastModifiedTime(path)),
                            maxBy(PROOF_JSON_RECENCY_COMPARATOR));
                }
            }
        } catch (IOException e) {
            logger.warn("Unable to scan pending blocks in {}", blockDirPath, e);
            return pendingBlocks;
        }

        logger.info("Evaluating {} distinct pending blocks on disk", proofJsons.size());
        long nextContiguousBlock = followingBlockNumber - 1;
        // Blocks at or after the restart point are replayed from events, so only older proofs can resume.
        for (final var entry :
                proofJsons.headMap(followingBlockNumber, false).descendingMap().entrySet()) {
            final long nextNumber = entry.getKey();
            if (nextNumber != nextContiguousBlock) {
                logger.info("No more contiguous blocks (#{} != #{})", nextNumber, nextContiguousBlock);
                break;
            }
            final var proofJsonPath = entry.getValue().path();
            final PendingProof pendingProof;
            try (final var data = new ReadableStreamingData(proofJsonPath)) {
                pendingProof = PendingProof.JSON.parseStrict(data);
            } catch (IOException | ParseException e) {
                logger.warn("Error reading pending proof metadata from {}; stopping recovery", proofJsonPath, e);
                break;
            }
            // Older proofs without timestamps or sibling hashes cannot be resumed.
            if (pendingProof.block() != nextNumber
                    || pendingProof.blockTimestamp() == null
                    || Objects.equals(pendingProof.blockTimestamp(), Timestamp.DEFAULT)
                    || pendingProof.siblingHashesFromPrevBlockRoot().size() != NUM_SIBLINGS_PER_BLOCK) {
                logger.warn(
                        "Pending proof metadata from {} doesn't match required fields; stopping recovery",
                        proofJsonPath);
                break;
            }
            final var contents = BlockFileUtils.findPendingFileForProof(proofJsonPath);
            if (contents.isEmpty()) {
                logger.warn("No pending block contents found for {}; stopping recovery", proofJsonPath);
                break;
            }
            final var contentsPath = contents.orElseThrow();
            final Block partialBlock;
            try (final var in = BlockFileUtils.openInputStream(contentsPath)) {
                partialBlock = parseBlock(in.readAllBytes(), maxReadDepth, maxReadSize);
            } catch (IOException | ParseException e) {
                logger.error("Error reading pending block contents from {}; stopping recovery", contentsPath, e);
                break;
            }
            pendingBlocks.addFirst(
                    new OnDiskPendingBlock(partialBlock.items(), pendingProof, proofJsonPath, contentsPath));
            nextContiguousBlock--;
        }
        return pendingBlocks;
    }

    private static Block parseBlock(final byte[] bytes, final int maxReadDepth, final int maxReadSize)
            throws ParseException {
        // Reject unknown fields while retaining the configured depth and size limits.
        return Block.PROTOBUF.parse(
                Bytes.wrap(bytes).toReadableSequentialData(), true, false, maxReadDepth, maxReadSize);
    }

    /**
     * Cleans up the pending block at the given path by deleting the corresponding {@code .pnd.json} and block contents.
     * @param contentsPath the path to the block contents
     */
    public static void cleanUpPendingBlock(@NonNull final Path contentsPath) {
        requireNonNull(contentsPath);
        final var proofJsonPath = BlockFileUtils.pendingProofPathFor(contentsPath);
        logger.info("Cleaning up pending block ({}, {})", proofJsonPath, contentsPath);
        if (!proofJsonPath.toFile().delete()) {
            logger.warn("Failed to delete pending proof metadata at {}", proofJsonPath);
        }
        if (!contentsPath.toFile().delete()) {
            logger.warn("Failed to delete pending block contents at {}", contentsPath);
        }
        final long number = BlockStreamAccess.extractBlockNumber(contentsPath);
        if (number < 0) {
            return;
        }
        // An upgrade can leave a copy of this block under the legacy 36-digit name; delete it so it is never resumed.
        for (final var stem :
                List.of(BlockFileUtils.blockNumberToFileName(number), String.format(Locale.ROOT, "%036d", number))) {
            for (final var suffix : List.of(".pnd.json", ".pnd.zstd", ".pnd.gz", ".pnd")) {
                try {
                    Files.deleteIfExists(contentsPath.resolveSibling(stem + suffix));
                } catch (IOException e) {
                    logger.warn("Failed to delete pending block file {}{}", stem, suffix, e);
                }
            }
        }
    }

    @Override
    public void openBlock(final long blockNumber) {
        if (state == State.OPEN) throw new IllegalStateException("Cannot initialize a FileBlockItemWriter twice");
        if (blockNumber < 0) throw new IllegalArgumentException("Block number must be non-negative");

        this.blockNumber = blockNumber;
        final var blockFilePath = nodeScopedBlockDir.resolve(BlockFileUtils.blockFileName(blockNumber));
        OutputStream out = null;
        try {
            if (!Files.exists(nodeScopedBlockDir)) {
                Files.createDirectories(nodeScopedBlockDir);
            }

            // Buffer raw items before compression, and compressed bytes before writing to disk.
            out = Files.newOutputStream(blockFilePath);
            out = new BufferedOutputStream(out, blockFileBufferInnerSizeBytes);
            out = BlockFileUtils.newZstdOutputStream(out);
            out = new BufferedOutputStream(out, blockFileBufferOuterSizeBytes);

            this.writableStreamingData = new WritableStreamingData(out);
        } catch (final IOException e) {
            // If an exception was thrown, we should close the stream if it was opened to prevent a resource leak.
            if (out != null) {
                try {
                    out.close();
                } catch (final IOException ex) {
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
            final Path markerFile = nodeScopedBlockDir.resolve(BlockFileUtils.markerFileName(blockNumber));
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
                Files.move(
                        nodeScopedBlockDir.resolve(BlockFileUtils.blockFileName(blockNumber)),
                        nodeScopedBlockDir.resolve(BlockFileUtils.pendingFileName(blockNumber)));
            } catch (IOException e) {
                logger.error("Error flushing pending block #{}", blockNumber, e);
                return;
            } finally {
                state = State.CLOSED;
            }
            final var json = PendingProof.JSON.toJSON(pendingProof);
            try {
                Files.writeString(pendingProofPath(nodeScopedBlockDir, blockNumber), json);
            } catch (IOException e) {
                logger.error("Error flushing pending proof metadata #{}", blockNumber, e);
            }
            logger.info(
                    "Flushed pending block #{} ({}, {})",
                    blockNumber,
                    nodeScopedBlockDir.resolve(BlockFileUtils.pendingFileName(blockNumber)),
                    pendingProofPath(nodeScopedBlockDir, blockNumber));
        } else {
            logger.warn("Block #{} flushed in non-OPEN state '{}'", blockNumber, state, new IllegalStateException());
        }
    }

    @Override
    public void flushIncompleteBlock() {
        // Persist the open, unproven block as a ".open.zstd" triage artifact: close the stream and rename the
        // partially-written ".blk.zstd" to ".open.zstd". We deliberately write no ".mf" completion marker and no
        // ".pnd.json" proof sidecar, so this block is never treated as a finished block nor picked up by pending-block
        // recovery. Best-effort: never throws.
        if (state != State.OPEN) {
            logger.warn("Cannot flush incomplete block #{} in non-OPEN state '{}'", blockNumber, state);
            return;
        }
        try {
            writableStreamingData.close();
            Files.move(
                    nodeScopedBlockDir.resolve(BlockFileUtils.blockFileName(blockNumber)),
                    nodeScopedBlockDir.resolve(BlockFileUtils.incompleteFileName(blockNumber)));
            logger.info(
                    "Flushed incomplete block #{} for triage to {}",
                    blockNumber,
                    nodeScopedBlockDir.resolve(BlockFileUtils.incompleteFileName(blockNumber)));
        } catch (final Exception e) {
            // Catch everything (not just IOException): per the BlockItemWriter contract this is best-effort and must
            // not throw, since the caller is already on the catastrophic-failure path.
            logger.error("Error flushing incomplete block #{}", blockNumber, e);
        } finally {
            state = State.CLOSED;
        }
    }
}
