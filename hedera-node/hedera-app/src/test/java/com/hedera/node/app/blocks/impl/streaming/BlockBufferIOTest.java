// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.generateRandomBlock;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.generateRandomBlocks;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.toBlockState;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.writeBlockToDiskV1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.hedera.node.app.blocks.impl.streaming.BlockBufferIO.BlockFromDisk;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlockBufferIOTest {

    private static final String testDir = "testDir";
    private static final File testDirFile = new File(testDir);

    private final BlockBufferIO bufferIO = new BlockBufferIO(testDir);

    @BeforeEach
    void beforeEach() throws IOException {
        cleanupDirectory();
    }

    @AfterEach
    void afterEach() throws IOException {
        cleanupDirectory();
    }

    private static void cleanupDirectory() throws IOException {
        if (!Files.exists(testDirFile.toPath())) {
            return;
        }

        Files.walkFileTree(testDirFile.toPath(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Test
    void testRead_noFiles() throws IOException {
        // don't create a directory or any sub files
        final List<BlockFromDisk> blocksFromDisk = bufferIO.read();
        assertThat(blocksFromDisk).isEmpty();
    }

    @Test
    void testRead_multipleDirectories() throws IOException {
        // given the test directory root, create multiple other directories
        final Instant now = Instant.now();
        long expectedBlockNumber = -1;
        int blockNum = 0;
        for (int i = 0; i < 5; ++i) {
            final long dirName = now.minusSeconds(10 + i).toEpochMilli();
            final File directory = new File(testDirFile, Long.toString(dirName));
            Files.createDirectories(directory.toPath());
            ++blockNum;
            final BlockState block = generateRandomBlock(blockNum, 25);
            final File file = new File(directory, "block-" + blockNum + ".bin");
            writeBlockToDiskV1(block, true, file);

            if (i == 0) {
                // this is the "latest" block we've written and thus when reading it should be the one returned
                expectedBlockNumber = blockNum;
            }
        }

        final File[] subdirectories = testDirFile.listFiles();
        assertThat(subdirectories).hasSize(5);

        final List<BlockFromDisk> blocksFromDisk = bufferIO.read();
        assertThat(blocksFromDisk).hasSize(1);
        final BlockFromDisk blockFromDisk = blocksFromDisk.getFirst();
        assertThat(blockFromDisk.blockNumber()).isEqualTo(expectedBlockNumber);
    }

    @Test
    void testRead_withInvalidSchemaId() throws Exception {
        final Instant now = Instant.now();
        final File directory = new File(testDirFile, Long.toString(now.toEpochMilli()));
        Files.createDirectories(directory.toPath());
        final ByteBuffer byteBuffer = ByteBuffer.allocate(27);
        byteBuffer.put((byte) 10); // the first byte is the schema version, which we expect to be 1
        byteBuffer.putLong(1); // block number
        byteBuffer.putLong(now.getEpochSecond()); // closed timestamp: seconds parts
        byteBuffer.putInt(now.getNano()); // closed timestamp: nanos part
        byteBuffer.put((byte) 1); // 1 -> true (block proof sent)
        byteBuffer.put((byte) 0); // 0 -> false (block not acked)
        byteBuffer.putInt(0); // payload length
        Files.write(
                new File(directory, "block-1.dat").toPath(),
                byteBuffer.array(),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW);
        byteBuffer.clear();
        byteBuffer.put((byte) 1); // valid schema number (1)
        byteBuffer.putLong(2); // block number
        byteBuffer.putLong(now.getEpochSecond()); // closed timestamp: seconds parts
        byteBuffer.putInt(now.getNano()); // closed timestamp: nanos part
        byteBuffer.put((byte) 1); // 1 -> true (block proof sent)
        byteBuffer.put((byte) 1); // 1 -> true (block acked)
        byteBuffer.putInt(0); // payload length
        Files.write(
                new File(directory, "block-2.dat").toPath(),
                byteBuffer.array(),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW);

        // when reading the blocks, the block with the invalid schema should be ignored

        final List<BlockFromDisk> blocksFromDisk = bufferIO.read();
        assertThat(blocksFromDisk).hasSize(1);
        final BlockFromDisk bfd = blocksFromDisk.getFirst();
        assertThat(bfd.blockNumber()).isEqualTo(2L);
    }

    @Test
    void testWrite_cleanupOldDirectories() throws Exception {
        // given the test directory root, create multiple other directories
        final Instant now = Instant.now();
        for (int i = 0; i < 5; ++i) {
            final long dirName = now.minusSeconds(10 + i).toEpochMilli();
            Files.createDirectories(new File(testDirFile, Long.toString(dirName)).toPath());
        }

        final File[] subdirectories = testDirFile.listFiles();
        assertThat(subdirectories).hasSize(5);

        final List<BlockState> blocksToWrite = generateRandomBlocks(5, 25);

        // write the blocks out. this should trigger a cleanup of other directories
        bufferIO.write(blocksToWrite, 5L);

        // now look at the subdirectories in the test root directory... there should be only 1 subdirectory
        final File[] postWriteSubdirectories = testDirFile.listFiles();
        assertThat(postWriteSubdirectories).hasSize(1);
        final File postWriteSubdirectory = postWriteSubdirectories[0];
        // make sure the name isn't one of the pre-write directories
        for (final File f : subdirectories) {
            if (postWriteSubdirectory.getName().equals(f.getName())) {
                fail("Post-write directory (" + postWriteSubdirectory.getAbsolutePath()
                        + ") found in pre-write list of directories");
            }
        }
    }

    @Test
    void testReadAndWrite() throws Exception {
        final long latestAckedBlockActual = 8;
        final int batchSize = 10;
        final List<BlockState> blocksToWrite = generateRandomBlocks(10, batchSize);
        bufferIO.write(blocksToWrite, latestAckedBlockActual);

        final List<BlockFromDisk> blocksFromDisk = bufferIO.read();
        assertThat(blocksFromDisk).hasSize(blocksToWrite.size());

        final Map<Long, BlockState> readBlocks = new HashMap<>();
        long latestAckedBlock = -1;

        for (final BlockFromDisk bfd : blocksFromDisk) {
            readBlocks.put(bfd.blockNumber(), toBlockState(bfd, batchSize));
            if (bfd.isAcknowledged()) {
                latestAckedBlock = Math.max(latestAckedBlock, bfd.blockNumber());
            }
        }

        for (final BlockState block : blocksToWrite) {
            final BlockState readBlock = readBlocks.get(block.blockNumber());
            assertThat(readBlock).isNotNull();
            assertThat(readBlock.numRequestsCreated()).isEqualTo(block.numRequestsCreated());
            for (int i = 0; i < readBlock.numRequestsCreated(); ++i) {
                assertThat(readBlock.getRequest(i)).isEqualTo(block.getRequest(i));
                assertThat(readBlock.closedTimestamp()).isEqualTo(block.closedTimestamp());
            }
        }
    }
}
