// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.generateRandomBlock;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.generateRandomBlocks;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.newBlockHeader;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.newBlockProof;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.newEventTransaction;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.newStateChanges;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.toBlockState;
import static com.hedera.node.app.blocks.impl.streaming.BlockTestUtils.writeBlockToDisk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.hedera.hapi.block.internal.BufferedBlock;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlockBufferIOTest {

    private static final String testDir = "testDir";
    private static final File testDirFile = new File(testDir);

    private final BlockBufferIO bufferIO = new BlockBufferIO(testDir, 512);

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
    void readWaitsWhileWriteHoldsTheLock() throws Exception {
        bufferIO.write(List.of(generateRandomBlock(1)), -1L);
        final CountDownLatch lockHeld = new CountDownLatch(1);
        final CountDownLatch releaseLock = new CountDownLatch(1);
        // stands in for a write in progress, which holds the same monitor and may delete the directory being read
        final Thread writer = Thread.ofPlatform().start(() -> {
            synchronized (bufferIO) {
                lockHeld.countDown();
                try {
                    releaseLock.await(5, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        final CompletableFuture<List<BufferedBlock>> read = new CompletableFuture<>();
        final Thread reader = Thread.ofPlatform().start(() -> {
            try {
                read.complete(bufferIO.read());
            } catch (final Exception e) {
                read.completeExceptionally(e);
            }
        });
        try {
            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(reader.join(Duration.ofMillis(300))).isFalse();
            assertThat(read).isNotDone();
        } finally {
            releaseLock.countDown();
            assertThat(writer.join(Duration.ofSeconds(5))).isTrue();
            assertThat(reader.join(Duration.ofSeconds(5))).isTrue();
        }
        assertThat(read.get()).hasSize(1);
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
    void failedWriteKeepsPreviousSnapshot() throws Exception {
        bufferIO.write(List.of(generateRandomBlock(1), generateRandomBlock(2), generateRandomBlock(3)), 0L);

        // block 5 is still open, so the next write fails part-way through its batch
        final BlockState openBlock = new BlockState(5L, 2_000L);
        openBlock.addItem(newBlockHeader(5L));
        assertThatThrownBy(() -> bufferIO.write(List.of(generateRandomBlock(6), generateRandomBlock(4), openBlock), 0L))
                .isInstanceOf(NullPointerException.class);

        assertThat(bufferIO.read()).extracting(BufferedBlock::blockNumber).containsExactlyInAnyOrder(1L, 2L, 3L);

        // the abandoned batch was written in block order, so it holds only the blocks before the failure
        final File[] abandoned = testDirFile.listFiles(file -> !file.getName().matches("\\d+"));
        assertThat(abandoned).hasSize(1);
        assertThat(abandoned[0].list()).containsExactly("block-4.bin");
    }

    @Test
    void leftoverTempDirectoryIsIgnoredAndRemovedByNextWrite() throws Exception {
        bufferIO.write(List.of(generateRandomBlock(1), generateRandomBlock(2)), 0L);

        // a valid block in a crashed write's directory, named newer than the complete snapshot
        final File leftover = new File(testDirFile, (Instant.now().toEpochMilli() + 60_000) + ".tmp");
        Files.createDirectories(leftover.toPath());
        writeBlockToDisk(generateRandomBlock(9), false, new File(leftover, "block-9.bin"));

        assertThat(bufferIO.read()).extracting(BufferedBlock::blockNumber).containsExactlyInAnyOrder(1L, 2L);

        bufferIO.write(List.of(generateRandomBlock(3)), 0L);

        final File[] directories = testDirFile.listFiles();
        assertThat(directories).hasSize(1);
        assertThat(directories[0].getName()).matches("\\d+");
        assertThat(bufferIO.read()).extracting(BufferedBlock::blockNumber).containsExactly(3L);
    }

    @Test
    void consecutiveWritesEachReplaceTheSnapshot() throws Exception {
        // back-to-back writes often land in the same millisecond
        for (long blockNumber = 1; blockNumber <= 100; ++blockNumber) {
            bufferIO.write(List.of(generateRandomBlock(blockNumber)), 0L);

            final File[] directories = testDirFile.listFiles();
            assertThat(directories).hasSize(1);
            assertThat(directories[0].list()).containsExactly("block-" + blockNumber + ".bin");
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

    @Test
    void readWritePreservesSerializedItemBytesAndDerivesTypes() throws Exception {
        // Build a block by adding items in their serialized form (the production path), capturing the exact bytes.
        final BlockState block = new BlockState(42L, 2_000L);
        final List<BlockItem> sourceItems =
                List.of(newBlockHeader(42L), newEventTransaction(), newStateChanges(), newBlockProof(42L));
        final List<Bytes> expectedBytes = new ArrayList<>();
        for (final BlockItem item : sourceItems) {
            final Bytes serialized = BlockItem.PROTOBUF.toBytes(item);
            block.addSerializedItem(serialized, item.item().kind());
            expectedBytes.add(serialized);
        }
        block.closeBlock();

        // Persist and reload through the real IO path.
        bufferIO.write(List.of(block), 42L);
        final List<BufferedBlock> readBlocks = bufferIO.read();
        assertThat(readBlocks).hasSize(1);
        final BlockState reloaded = toBlockState(readBlocks.getFirst());

        // Every item's serialized bytes survive the round-trip byte-for-byte (no deserialize/re-serialize), and the
        // item type is correctly re-derived from the bytes on load.
        assertThat(reloaded.itemCount()).isEqualTo(sourceItems.size());
        for (int i = 0; i < sourceItems.size(); i++) {
            final BlockState.BufferedItem bufferedItem = reloaded.bufferedItem(i);
            assertThat(bufferedItem).isNotNull();
            assertThat(bufferedItem.serializedItem()).isEqualTo(expectedBytes.get(i));
            assertThat(bufferedItem.itemType())
                    .isEqualTo(sourceItems.get(i).item().kind());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"c03e01", "0a03c03e01"})
    void readWritePreservesUnknownFieldsInSignedTransaction(final String signedTxHex) throws Exception {
        // Field 1000, varint 1, either in SignedTransaction or inside its bodyBytes (field 1).
        final var signedTxBytes = Bytes.fromHex(signedTxHex);
        final var item = BlockItem.newBuilder().signedTransaction(signedTxBytes).build();
        final var serializedItem = BlockItem.PROTOBUF.toBytes(item);
        final var block = new BlockState(42L, 2_000L);
        final var header = newBlockHeader(42L);
        block.addSerializedItem(
                BlockItem.PROTOBUF.toBytes(header), header.item().kind());
        block.addSerializedItem(serializedItem, item.item().kind());
        block.closeBlock();

        bufferIO.write(List.of(block), 42L);
        final var readBlocks = bufferIO.read();
        assertThat(readBlocks).hasSize(1);
        final var reloaded = toBlockState(readBlocks.getFirst());

        assertThat(reloaded.bufferedItem(1).serializedItem()).isEqualTo(serializedItem);
        // BlockState's strict BlockItem parser must leave the opaque transaction bytes intact, too.
        assertThat(reloaded.blockItem(1).signedTransactionOrThrow()).isEqualTo(signedTxBytes);
    }

    @Test
    void readSkipsBufferedBlockWithUnknownFields() throws IOException {
        final var block = generateRandomBlock(1);
        bufferIO.write(List.of(block), 0);
        final var directory = testDirFile.listFiles()[0];
        final var file = directory.listFiles()[0].toPath();
        final var original = Files.readAllBytes(file);
        final var corrupted = ByteBuffer.allocate(original.length + 3);
        corrupted.putInt(ByteBuffer.wrap(original).getInt() + 3);
        corrupted.put(original, Integer.BYTES, original.length - Integer.BYTES);
        // Valid protobuf field 1000, varint 1, which is not part of BufferedBlock.
        corrupted.put(Bytes.fromHex("c03e01").toByteArray());
        Files.write(file, corrupted.array());

        assertThat(bufferIO.read()).isEmpty();
    }

    @Test
    void insufficientReadDepthIgnoresBlocks() throws IOException {
        final List<BlockState> blocksToWrite = generateRandomBlocks(10);
        bufferIO.write(blocksToWrite, 0);

        // The persisted BufferedBlock embeds nested messages (its timestamps and the BlockBytes wrapper), so a max
        // read depth of 0 (which disallows any nested message) makes every block fail to parse and be skipped. Note
        // the serialized-bytes format is intentionally shallow: block items are stored as opaque bytes rather than
        // deeply nested BlockItem messages, so buffer restoration is no longer sensitive to per-item nesting depth.
        final var restrictedSubject = new BlockBufferIO(testDir, 0);
        final List<BufferedBlock> blocksFromDisk = restrictedSubject.read();
        assertThat(blocksFromDisk).isEmpty();
    }
}
