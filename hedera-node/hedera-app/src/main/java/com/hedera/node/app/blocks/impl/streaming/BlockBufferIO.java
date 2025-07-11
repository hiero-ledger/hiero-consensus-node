// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.BlockItemSet;
import org.hiero.block.api.PublishStreamRequest;

/**
 * This class supports reading and writing a series of blocks to disk.
 */
public class BlockBufferIO {
    private static final Logger logger = LogManager.getLogger(BlockBufferIO.class);

    private static final byte BYTE_ZERO = (byte) 0;
    private static final byte BYTE_ONE = (byte) 1;

    /**
     * The root directory where blocks are stored.
     */
    private final File rootDirectory;

    public BlockBufferIO(final String rootDirectory) {
        this.rootDirectory = new File(requireNonNull(rootDirectory));
    }

    /**
     * Write the specified blocks to disk.
     *
     * @param blocks the blocks to write to disk
     * @param latestAcknowledgedBlockNumber the latest block number acknowledged
     * @throws IOException if there is an error writing the block data to disk
     */
    public void write(final List<BlockState> blocks, final long latestAcknowledgedBlockNumber) throws IOException {
        new Writer(blocks, latestAcknowledgedBlockNumber).write();
    }

    /**
     * Read the latest set of blocks persisted to disk.
     *
     * @return a list of blocks from disk
     * @throws IOException if there is an error reading the block data from disk
     */
    public List<BlockFromDisk> read() throws IOException {
        return new Reader().read();
    }

    /**
     * Record representing a single block from disk.
     *
     * @param blockNumber the block number
     * @param closedTimestamp the timestamp of when the block was closed
     * @param isProofSent true if the block proof was sent to a block node, else false
     * @param isAcknowledged true if the block was acknowledged, else false
     * @param items list of items that make up the block
     */
    public record BlockFromDisk(
            long blockNumber,
            Instant closedTimestamp,
            boolean isProofSent,
            boolean isAcknowledged,
            List<BlockItem> items) {}

    private class Reader {

        private List<BlockFromDisk> read() throws IOException {
            final File[] files = rootDirectory.listFiles();

            if (files == null) {
                logger.warn(
                        "Block buffer directory not found and/or no files present (directory: {})",
                        rootDirectory.getAbsolutePath());
                return List.of();
            }

            File dirToRead = null;
            long dirMillis = -1;

            // determine if there are multiple subdirectories, if so select the latest one to read
            for (final File file : files) {
                if (!file.isDirectory()) {
                    continue;
                }

                final String dirName = file.getName();
                final long millis;
                try {
                    millis = Long.parseLong(dirName);
                } catch (final NumberFormatException e) {
                    // unexpected directory name... ignore it
                    continue;
                }

                if (millis > dirMillis) {
                    // newer directory found
                    dirToRead = file;
                    dirMillis = millis;
                }
            }

            if (dirToRead == null) {
                logger.warn("No valid block buffer directories found in: {}", rootDirectory.getAbsolutePath());
                return List.of();
            }

            return read(dirToRead);
        }

        private List<BlockFromDisk> read(final File directory) throws IOException {
            logger.debug("Reading blocks from directory: {}", directory.getAbsolutePath());
            final List<File> files;
            try (final Stream<Path> stream = Files.list(directory.toPath())) {
                files = stream.map(Path::toFile).toList();
            }

            final List<BlockFromDisk> blocks = new ArrayList<>(files.size());

            for (final File file : files) {
                try {
                    final BlockFromDisk bfd = readBlockFile(file);
                    logger.debug("Read block {} from file: {}", bfd.blockNumber, file.getAbsolutePath());
                    blocks.add(bfd);
                } catch (final Exception e) {
                    logger.warn("Failed to read block file; ignoring block (file={})", file.getAbsolutePath(), e);
                }
            }

            return blocks;
        }

        private BlockFromDisk readBlockFile(final File file) throws IOException, ParseException {
            try (final RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                final FileChannel fileChannel = raf.getChannel();
                final MappedByteBuffer byteBuffer = fileChannel.map(MapMode.READ_ONLY, 0, fileChannel.size());

                final byte schemaVersion = byteBuffer.get();
                if (BYTE_ONE != schemaVersion) {
                    throw new IllegalStateException(
                            "Block file contained an unexpected schema version (expected: 1, found: " + schemaVersion
                                    + ")");
                }

                final long blockNumber = byteBuffer.getLong();
                final long closedSecondsPart = byteBuffer.getLong();
                final int closedNanosPart = byteBuffer.getInt();
                final byte isProofSent = byteBuffer.get();
                final byte isAcknowledged = byteBuffer.get();
                final int payloadBytesLength = byteBuffer.getInt();
                final byte[] payload = new byte[payloadBytesLength];
                byteBuffer.get(payload);
                final Block blk = Block.PROTOBUF.parse(Bytes.wrap(payload));

                return new BlockFromDisk(
                        blockNumber,
                        Instant.ofEpochSecond(closedSecondsPart, closedNanosPart),
                        toBoolean(isProofSent),
                        toBoolean(isAcknowledged),
                        blk.items());
            }
        }

        private static boolean toBoolean(final byte data) {
            return BYTE_ONE == data;
        }
    }

    private class Writer {
        private final List<BlockState> blocks;
        private final long latestAcknowledgedBlockNumber;
        private final ByteBuffer fileHeaderBuffer = ByteBuffer.allocate(27);

        Writer(final List<BlockState> blocks, final long latestAcknowledgedBlockNumber) {
            this.blocks = new ArrayList<>(requireNonNull(blocks));
            this.latestAcknowledgedBlockNumber = latestAcknowledgedBlockNumber;
        }

        private void write() throws IOException {
            final Instant now = Instant.now();
            final File directory = new File(rootDirectory, Long.toString(now.toEpochMilli()));
            final Path directoryPath = directory.toPath();
            Files.createDirectories(directoryPath);

            logger.debug(
                    "Created new block buffer directory: {}",
                    directoryPath.toFile().getAbsolutePath());

            // write metadata file
            for (final BlockState block : blocks) {
                final String fileName = "block-" + block.blockNumber() + ".bin";
                final Path path = new File(directory, fileName).toPath();
                fileHeaderBuffer.clear();
                // add a schema version, hardcoded to 1 (in case the format needs to change in the future)
                fileHeaderBuffer.put(BYTE_ONE);
                fileHeaderBuffer.putLong(block.blockNumber());

                // store the closed timestamp
                final Instant closedTimestamp = block.closedTimestamp();
                fileHeaderBuffer.putLong(closedTimestamp.getEpochSecond());
                fileHeaderBuffer.putInt(closedTimestamp.getNano());

                fileHeaderBuffer.put(block.isBlockProofSent() ? BYTE_ONE : BYTE_ZERO);
                fileHeaderBuffer.put(block.blockNumber() <= latestAcknowledgedBlockNumber ? BYTE_ONE : BYTE_ZERO);

                // collect the block items to write
                final List<BlockItem> items = new ArrayList<>();

                for (int i = 0; i < block.numRequestsCreated(); ++i) {
                    final PublishStreamRequest req = block.getRequest(i);
                    if (req != null) {
                        final BlockItemSet bis = req.blockItemsOrElse(BlockItemSet.DEFAULT);
                        items.addAll(bis.blockItems());
                    }
                }

                final Block blk = new Block(items);
                final Bytes blkBytes = Block.PROTOBUF.toBytes(blk);
                final int length =
                        (int) blkBytes.length(); // Bytes#length returns a long, but the actual data is an int

                fileHeaderBuffer.putInt(length);

                // write the header
                Files.write(
                        path,
                        fileHeaderBuffer.array(),
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.TRUNCATE_EXISTING);
                // write the block payload
                Files.write(path, blkBytes.toByteArray(), StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                if (logger.isDebugEnabled()) {
                    final int numBytes = fileHeaderBuffer.capacity() + length;
                    logger.debug(
                            "Block {} written to file: {} (bytes: {})",
                            block.blockNumber(),
                            path.toFile().getAbsolutePath(),
                            numBytes);
                }
            }

            // Clean up any other block buffer directories
            Files.walkFileTree(rootDirectory.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                    if (dir.equals(directoryPath)) {
                        // avoid checking the directory we just created
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    logger.debug("Deleting old block buffer file: {}", file);
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                    if (!dir.equals(directoryPath) && !dir.equals(rootDirectory.toPath())) {
                        logger.debug("Deleting old block buffer directory: {}", dir);
                        // delete the directory (after making sure it isn't the new directory)
                        Files.delete(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
