// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.generateRandomBlock;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.generateRandomBlocks;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.toBlockState;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.writeBlockToDisk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.hedera.hapi.block.internal.BufferedBlock;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
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
        final List<BufferedBlock> blocksFromDisk = bufferIO.read();
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
            final BlockState block = generateRandomBlock(blockNum);
            final File file = new File(directory, "block-" + blockNum + ".bin");
            writeBlockToDisk(block, true, file);

            if (i == 0) {
                // this is the "latest" block we've written and thus when reading it should be the one returned
                expectedBlockNumber = blockNum;
            }
        }

        final File[] subdirectories = testDirFile.listFiles();
        assertThat(subdirectories).hasSize(5);

        final List<BufferedBlock> blocksFromDisk = bufferIO.read();
        assertThat(blocksFromDisk).hasSize(1);
        final BufferedBlock blockFromDisk = blocksFromDisk.getFirst();
        assertThat(blockFromDisk.blockNumber()).isEqualTo(expectedBlockNumber);
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

        final List<BlockState> blocksToWrite = generateRandomBlocks(5);

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
        final List<BlockState> blocksToWrite = generateRandomBlocks(10);
        bufferIO.write(blocksToWrite, latestAckedBlockActual);

        final List<BufferedBlock> blocksFromDisk = bufferIO.read();
        assertThat(blocksFromDisk).hasSize(blocksToWrite.size());

        final Map<Long, BlockState> readBlocks = new HashMap<>();
        long latestAckedBlock = -1;

        for (final BufferedBlock bfd : blocksFromDisk) {
            readBlocks.put(bfd.blockNumber(), toBlockState(bfd));
            if (bfd.isAcknowledged()) {
                latestAckedBlock = Math.max(latestAckedBlock, bfd.blockNumber());
            }
        }

        for (final BlockState block : blocksToWrite) {
            final BlockState readBlock = readBlocks.get(block.blockNumber());
            assertThat(readBlock).isNotNull();
            assertThat(readBlock.itemCount()).isEqualTo(block.itemCount());
            assertThat(readBlock.closedTimestamp()).isEqualTo(block.closedTimestamp());
            for (int i = 0; i < block.itemCount(); ++i) {
                assertThat(readBlock.blockItem(i)).isEqualTo(block.blockItem(i));
            }
        }
    }
}
