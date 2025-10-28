// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.BufferedBlock;
import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.base.Timestamp;
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

/**
 * This class supports reading and writing a series of blocks to disk.
 * <p>
 * Block files are written into a directory whose name is the current (at the time of writing) timestamp in milliseconds.
 * This naming structure allows for an easy way to find the most recently written batch of blocks. Each block file is
 * named: {@code block-$BlockNumber.bin}
 */
public class BlockBufferIO {
    private static final Logger logger = LogManager.getLogger(BlockBufferIO.class);

    /**
     * The root directory where blocks are stored.
     */
    private final File rootDirectory;

    /**
     * Constructor for the block buffer IO operations.
     *
     * @param rootDirectory the root directory that will contain subdirectories containing the block files.
     */
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
    public List<BufferedBlock> read() throws IOException {
        return new Reader().read();
    }

    /**
     * Utility class that contains logic related to reading blocks from disk.
     */
    private class Reader {

        private List<BufferedBlock> read() throws IOException {
            final File[] files = rootDirectory.listFiles();

            if (files == null) {
                logger.info(
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

        /**
         * Given the specified directory, read all valid blocks from disk. If a block file on disk is corrupt or
         * otherwise invalid, it will be skipped.
         *
         * @param directory the directory containing the blocks to read
         * @return a list of blocks
         * @throws IOException if there is an error reading the block files
         */
        private List<BufferedBlock> read(final File directory) throws IOException {
            logger.debug("Reading blocks from directory: {}", directory.getAbsolutePath());
            final List<File> files;
            try (final Stream<Path> stream = Files.list(directory.toPath())) {
                files = stream.map(Path::toFile).toList();
            }

            final List<BufferedBlock> blocks = new ArrayList<>(files.size());

            for (final File file : files) {
                try {
                    final BufferedBlock bufferedBlock = readBlockFile(file);
                    logger.debug(
                            "Block {} (items: {}) read from file: {}",
                            bufferedBlock.blockNumber(),
                            bufferedBlock.block().items().size(),
                            file.getAbsolutePath());
                    blocks.add(bufferedBlock);
                } catch (final Exception e) {
                    logger.warn("Failed to read block file; ignoring block (file={})", file.getAbsolutePath(), e);
                }
            }

            return blocks;
        }

        /**
         * Reads a specified block file.
         *
         * @param file the block file to read
         * @return the block
         * @throws IOException if there was an error reading the block file
         * @throws ParseException if there was an error parsing the block file
         */
        private BufferedBlock readBlockFile(final File file) throws IOException, ParseException {
            try (final RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                final FileChannel fileChannel = raf.getChannel();
                final MappedByteBuffer byteBuffer = fileChannel.map(MapMode.READ_ONLY, 0, fileChannel.size());

                final int length = byteBuffer.getInt();
                final byte[] payload = new byte[length];
                byteBuffer.get(payload);
                final Bytes bytes = Bytes.wrap(payload);

                return BufferedBlock.PROTOBUF.parse(bytes);
            }
        }
    }

    /**
     * Utility class that contains logic related to writing blocks to disk.
     */
    private class Writer {
        private final List<BlockState> blocks;
        private final long latestAcknowledgedBlockNumber;

        Writer(final List<BlockState> blocks, final long latestAcknowledgedBlockNumber) {
            this.blocks = new ArrayList<>(requireNonNull(blocks));
            this.latestAcknowledgedBlockNumber = latestAcknowledgedBlockNumber;
        }

        /**
         * Performs the actual writing of blocks to disk. This will also perform cleanup of any older block directories.
         *
         * @throws IOException if there was an error writing the blocks to disk
         */
        private void write() throws IOException {
            final Instant now = Instant.now();
            final File directory = new File(rootDirectory, Long.toString(now.toEpochMilli()));
            final Path directoryPath = directory.toPath();
            Files.createDirectories(directoryPath);

            logger.debug(
                    "Created new block buffer directory: {}",
                    directoryPath.toFile().getAbsolutePath());

            for (final BlockState block : blocks) {
                final String fileName = "block-" + block.blockNumber() + ".bin";
                final Path path = new File(directory, fileName).toPath();

                writeBlock(path, block);
            }

            cleanupOldFiles(directoryPath);
        }

        /**
         * Writes the specified block to the specified path.
         *
         * @param path the path to write the block to
         * @param block the block to export
         * @throws IOException if there was an error while writing the block to disk
         */
        private void writeBlock(final Path path, final BlockState block) throws IOException {
            // collect the block items to write
            final List<BlockItem> items = new ArrayList<>(block.itemCount());

            for (int i = 0; i < block.itemCount(); ++i) {
                final BlockItem item = block.blockItem(i);
                if (item != null) {
                    items.add(item);
                }
            }

            final Block blk = new Block(items);
            final Instant closedInstant = block.closedTimestamp();

            final Timestamp closedTimestamp = Timestamp.newBuilder()
                    .seconds(closedInstant.getEpochSecond())
                    .nanos(closedInstant.getNano())
                    .build();
            final BufferedBlock bufferedBlock = BufferedBlock.newBuilder()
                    .blockNumber(block.blockNumber())
                    .closedTimestamp(closedTimestamp)
                    .isAcknowledged(block.blockNumber() <= latestAcknowledgedBlockNumber)
                    .block(blk)
                    .build();

            /*
            Build the final byte array to be written to disk. This will consist of the first 4 bytes being the
            length of the buffered block data, followed by the buffered block data: [length][data]
             */

            final Bytes payload = BufferedBlock.PROTOBUF.toBytes(bufferedBlock);
            final int length = (int) payload.length();
            final byte[] lenArray = ByteBuffer.allocate(4).putInt(length).array();
            final Bytes len = Bytes.wrap(lenArray);
            final Bytes bytes = Bytes.merge(len, payload);

            Files.write(
                    path,
                    bytes.toByteArray(),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.TRUNCATE_EXISTING);
            if (logger.isDebugEnabled()) {
                logger.debug(
                        "Block {} (items: {}) written to file: {} (bytes: {})",
                        block.blockNumber(),
                        items.size(),
                        path.toFile().getAbsolutePath(),
                        bytes.length());
            }
        }

        /**
         * Remove old directories and files that were from previous buffer exports.
         *
         * @param newestDirectory the directory containing the latest export
         * @throws IOException if there was an error cleaning up the directories/files
         */
        private void cleanupOldFiles(final Path newestDirectory) throws IOException {
            // Clean up any other block buffer directories
            Files.walkFileTree(rootDirectory.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                    if (dir.equals(newestDirectory)) {
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
                    if (!dir.equals(newestDirectory) && !dir.equals(rootDirectory.toPath())) {
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
