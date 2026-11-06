// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlockStreamAccessTest {

    @TempDir
    Path tempDir;

    private static final Block SAMPLE_BLOCK = Block.newBuilder()
            .items(BlockItem.newBuilder()
                    .blockHeader(BlockHeader.newBuilder().number(42L).build())
                    .build())
            .build();

    @Test
    void blockFrom_withPlainBlockFile_returnsBlock() throws IOException {
        final var blockBytes = Block.PROTOBUF.toBytes(SAMPLE_BLOCK).toByteArray();
        final var blockFile = tempDir.resolve("000000000000000000042.blk");
        Files.write(blockFile, blockBytes);

        final var result = BlockStreamAccess.blockFrom(blockFile);

        assertEquals(SAMPLE_BLOCK, result);
    }

    @Test
    void blockFrom_withGzippedBlockFile_returnsBlock() throws IOException {
        final var blockBytes = Block.PROTOBUF.toBytes(SAMPLE_BLOCK).toByteArray();
        final var gzippedFile = tempDir.resolve("000000000000000000042.blk.gz");
        try (var baos = new ByteArrayOutputStream();
                var gzip = new GZIPOutputStream(baos)) {
            gzip.write(blockBytes);
            gzip.finish();
            Files.write(gzippedFile, baos.toByteArray());
        }

        final var result = BlockStreamAccess.blockFrom(gzippedFile);

        assertEquals(SAMPLE_BLOCK, result);
    }

    @Test
    void blockFrom_withZstandardBlockFile_returnsBlock() throws IOException {
        final var path = tempDir.resolve("0000000000000000042.blk.zstd");
        Files.write(path, zstandardFrame(SAMPLE_BLOCK));

        assertEquals(SAMPLE_BLOCK, BlockStreamAccess.blockFrom(path));
    }

    @Test
    void readBlocks_ordersMixedWidthAndCompressionFilesByBlockNumber() throws IOException {
        final var earlier = blockWithNumber(2);
        final var later = blockWithNumber(10);
        Files.write(
                tempDir.resolve("000000000000000000000000000000000002.blk"),
                Block.PROTOBUF.toBytes(earlier).toByteArray());
        Files.write(tempDir.resolve("0000000000000000010.blk.zstd"), zstandardFrame(later));

        assertEquals(List.of(earlier, later), BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocksIgnoringMarkers(tempDir));
    }

    @Test
    void zstandardCompleteBlockRequiresExactMarker() throws IOException {
        final var path = tempDir.resolve("0000000000000000042.blk.zstd");
        Files.write(path, zstandardFrame(SAMPLE_BLOCK));
        Files.createFile(tempDir.resolve("0000000000000000042.mf.zstd"));
        assertFalse(BlockStreamAccess.isBlockFile(path, true));

        Files.createFile(tempDir.resolve("0000000000000000042.mf"));
        assertTrue(BlockStreamAccess.isBlockFile(path, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {".pnd", ".pnd.gz", ".pnd.zstd"})
    void pendingBlockRequiresProofEvenWhenIgnoringMarkers(final String suffix) throws IOException {
        final var path = Files.createFile(tempDir.resolve("0000000000000000042" + suffix));
        Files.createFile(tempDir.resolve("0000000000000000042.mf"));
        assertFalse(BlockStreamAccess.isBlockFile(path, false));
        assertFalse(BlockStreamAccess.isBlockFile(path, true));

        final var proof = tempDir.resolve("0000000000000000042.pnd.json");
        Files.createDirectory(proof);
        assertFalse(BlockStreamAccess.isBlockFile(path, false));
        Files.delete(proof);
        Files.createFile(proof);
        assertTrue(BlockStreamAccess.isBlockFile(path, false));
        assertTrue(BlockStreamAccess.isBlockFile(path, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {".open.zstd", ".pnd.json", ".blk.zstd.tmp", ".blk.zip", ".pnd.gz.tmp"})
    void excludesIncompleteAndUnsupportedArtifacts(final String suffix) throws IOException {
        final var path = Files.createFile(tempDir.resolve("0000000000000000042" + suffix));

        assertFalse(BlockStreamAccess.isBlockFile(path, false));
        assertFalse(BlockStreamAccess.isBlockFile(path.toFile()));
        assertEquals(-1, BlockStreamAccess.extractBlockNumber(path));
    }

    @Test
    void tolerantReaderSkipsTruncatedZstandardBlock() throws IOException {
        Files.write(tempDir.resolve("0000000000000000041.blk.zstd"), zstandardFrame(SAMPLE_BLOCK));
        final var frame = zstandardFrame(SAMPLE_BLOCK);
        Files.write(tempDir.resolve("0000000000000000042.blk.zstd"), java.util.Arrays.copyOf(frame, frame.length - 1));

        assertThrows(
                RuntimeException.class, () -> BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocksIgnoringMarkers(tempDir));
        assertEquals(
                List.of(SAMPLE_BLOCK),
                BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocksIgnoringMarkersAndReadErrors(tempDir));
    }

    @Test
    void readerIncludesProvenPendingZstandardBlocksAndExcludesOrphans() throws IOException {
        Files.write(tempDir.resolve("0000000000000000042.pnd.zstd"), zstandardFrame(SAMPLE_BLOCK));
        Files.createFile(tempDir.resolve("0000000000000000042.pnd.json"));
        Files.write(tempDir.resolve("0000000000000000043.pnd.zstd"), zstandardFrame(blockWithNumber(43)));
        Files.write(tempDir.resolve("0000000000000000044.open.zstd"), zstandardFrame(blockWithNumber(44)));

        assertEquals(List.of(SAMPLE_BLOCK), BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(tempDir));
        assertEquals(List.of(SAMPLE_BLOCK), BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocksIgnoringMarkers(tempDir));
    }

    @Test
    void readerRejectsZstandardChecksumCorruption() throws IOException {
        final var output = new ByteArrayOutputStream();
        try (final var compressed = BlockFileUtils.newZstdOutputStream(output)) {
            compressed.write(Block.PROTOBUF.toBytes(SAMPLE_BLOCK).toByteArray());
        }
        final var frame = output.toByteArray();
        frame[frame.length - 1] ^= 1;
        final var path = Files.write(tempDir.resolve("0000000000000000042.blk.zstd"), frame);

        assertThrows(RuntimeException.class, () -> BlockStreamAccess.blockFrom(path));
        assertEquals(List.of(), BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocksIgnoringMarkersAndReadErrors(tempDir));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void retainedPendingDuplicateDoesNotReplaceMarkedCompleteBlock(final boolean checkMarkers) throws IOException {
        final var completed = Block.newBuilder()
                .items(SAMPLE_BLOCK.items().getFirst(), BlockItem.DEFAULT)
                .build();
        final var before = blockWithNumber(41);
        final var after = blockWithNumber(43);
        Files.write(
                tempDir.resolve("0000000000000000041.blk"),
                Block.PROTOBUF.toBytes(before).toByteArray());
        Files.createFile(tempDir.resolve("0000000000000000041.mf"));
        final var retained = tempDir.resolve("000000000000000000000000000000000042.pnd");
        Files.write(retained, Block.PROTOBUF.toBytes(SAMPLE_BLOCK).toByteArray());
        final var retainedProof = Files.createFile(tempDir.resolve("000000000000000000000000000000000042.pnd.json"));
        Files.setLastModifiedTime(retainedProof, FileTime.fromMillis(9_000));
        Files.write(tempDir.resolve("0000000000000000042.blk.zstd"), zstandardFrame(completed));
        final var marker = Files.createFile(tempDir.resolve("0000000000000000042.mf"));
        Files.setLastModifiedTime(marker, FileTime.fromMillis(1_000));
        Files.write(
                tempDir.resolve("0000000000000000043.blk"),
                Block.PROTOBUF.toBytes(after).toByteArray());
        Files.createFile(tempDir.resolve("0000000000000000043.mf"));
        final var unmarked = blockWithNumber(44);
        Files.write(
                tempDir.resolve("0000000000000000044.blk"),
                Block.PROTOBUF.toBytes(unmarked).toByteArray());

        assertEquals(
                checkMarkers ? List.of(before, completed, after) : List.of(before, completed, after, unmarked),
                BlockStreamAccess.readBlocks(tempDir, checkMarkers).toList());
        assertTrue(Files.isRegularFile(retained));
        assertTrue(Files.isRegularFile(retainedProof));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void duplicatePendingSnapshotsUseNewestProofAheadOfUnmarkedComplete(final boolean checkMarkers) throws IOException {
        final var newest = Block.newBuilder()
                .items(SAMPLE_BLOCK.items().getFirst(), BlockItem.DEFAULT)
                .build();
        final var oldPending = Files.write(
                tempDir.resolve("000000000000000000000000000000000042.pnd"),
                Block.PROTOBUF.toBytes(SAMPLE_BLOCK).toByteArray());
        final var oldProof = Files.createFile(tempDir.resolve("000000000000000000000000000000000042.pnd.json"));
        Files.setLastModifiedTime(oldProof, FileTime.fromMillis(2_000));
        Files.setLastModifiedTime(oldPending, FileTime.fromMillis(8_000));
        final var newPending = Files.write(tempDir.resolve("0000000000000000042.pnd.zstd"), zstandardFrame(newest));
        final var newProof = Files.createFile(tempDir.resolve("0000000000000000042.pnd.json"));
        Files.setLastModifiedTime(newProof, FileTime.fromMillis(3_000));
        Files.setLastModifiedTime(newPending, FileTime.fromMillis(1_000));
        final var unmarked = Files.write(
                tempDir.resolve("42.blk"), Block.PROTOBUF.toBytes(SAMPLE_BLOCK).toByteArray());
        Files.setLastModifiedTime(unmarked, FileTime.fromMillis(9_000));

        assertEquals(
                List.of(newest),
                BlockStreamAccess.readBlocks(tempDir, checkMarkers).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void duplicatePendingSnapshotsWithEqualProofTimesUseDeterministicPathOrder(final boolean checkMarkers)
            throws IOException {
        final var preferred = Block.newBuilder()
                .items(SAMPLE_BLOCK.items().getFirst(), BlockItem.DEFAULT)
                .build();
        Files.write(
                tempDir.resolve("000000000000000000000000000000000042.pnd"),
                Block.PROTOBUF.toBytes(SAMPLE_BLOCK).toByteArray());
        final var oldProof = Files.createFile(tempDir.resolve("000000000000000000000000000000000042.pnd.json"));
        Files.write(tempDir.resolve("0000000000000000042.pnd.zstd"), zstandardFrame(preferred));
        final var newProof = Files.createFile(tempDir.resolve("0000000000000000042.pnd.json"));
        Files.setLastModifiedTime(oldProof, FileTime.fromMillis(2_000));
        Files.setLastModifiedTime(newProof, FileTime.fromMillis(2_000));

        assertEquals(
                List.of(preferred),
                BlockStreamAccess.readBlocks(tempDir, checkMarkers).toList());
    }

    @Test
    void blockFrom_withInvalidBytes_throwsRuntimeException() throws IOException {
        final var blockFile = tempDir.resolve("000000000000000000001.blk");
        Files.write(blockFile, new byte[] {0x06});

        assertThrows(RuntimeException.class, () -> BlockStreamAccess.blockFrom(blockFile));
    }

    @Test
    void readBlocksIgnoringMarkersAndReadErrors_skipsUnreadableBlocks() throws IOException {
        final var blockBytes = Block.PROTOBUF.toBytes(SAMPLE_BLOCK).toByteArray();
        final var readableBlockFile = tempDir.resolve("000000000000000000041.blk");
        final var unreadableGzippedBlockFile = tempDir.resolve("000000000000000000042.blk.gz");
        Files.write(readableBlockFile, blockBytes);
        Files.write(unreadableGzippedBlockFile, new byte[0]);

        assertThrows(
                RuntimeException.class, () -> BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocksIgnoringMarkers(tempDir));

        final var blocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocksIgnoringMarkersAndReadErrors(tempDir);

        assertEquals(List.of(SAMPLE_BLOCK), blocks);
    }

    private static Block blockWithNumber(final long number) {
        return Block.newBuilder()
                .items(BlockItem.newBuilder()
                        .blockHeader(BlockHeader.newBuilder().number(number).build())
                        .build())
                .build();
    }

    // A standard Zstandard frame containing a single raw block and no checksum.
    private static byte[] zstandardFrame(final Block block) throws IOException {
        final var bytes = Block.PROTOBUF.toBytes(block).toByteArray();
        final var output = new ByteArrayOutputStream();
        output.write(new byte[] {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 0x20, (byte) bytes.length});
        final int header = (bytes.length << 3) | 1;
        output.write(new byte[] {(byte) header, (byte) (header >>> 8), (byte) (header >>> 16)});
        output.write(bytes);
        return output.toByteArray();
    }
}
