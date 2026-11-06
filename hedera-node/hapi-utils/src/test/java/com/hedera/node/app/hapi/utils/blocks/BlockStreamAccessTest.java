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
    void tolerantReaderSkipsTruncatedAndEmptyZstandardBlocks() throws IOException {
        Files.write(tempDir.resolve("0000000000000000041.blk.zstd"), zstandardFrame(SAMPLE_BLOCK));
        final var frame = zstandardFrame(SAMPLE_BLOCK);
        Files.write(tempDir.resolve("0000000000000000042.blk.zstd"), java.util.Arrays.copyOf(frame, frame.length - 1));
        Files.write(tempDir.resolve("0000000000000000043.blk.zstd"), new byte[0]);

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
