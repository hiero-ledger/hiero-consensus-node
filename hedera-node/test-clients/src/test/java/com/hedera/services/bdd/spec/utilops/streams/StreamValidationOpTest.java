// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_PARENT_DIR;
import static com.hedera.services.bdd.spec.utilops.streams.StreamValidationOp.findCompleteBlockIn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.output.BlockFooter;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.node.app.hapi.utils.blocks.BlockFileUtils;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.spec.HapiSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamValidationOpTest {

    private static final long BLOCK_NUMBER = 42L;
    private static final Bytes ROOT_HASH = Bytes.wrap(new byte[] {1, 2, 3});

    /**
     * Regression for the cross-node fallback bug in {@code findCompleteBlockIn}: each node writes
     * block files under its own {@code block-<shard>.<realm>.<nodeAccountId>/} subdirectory, and
     * DAB can renumber those account IDs on upgrades. The pre-fix implementation resolved the
     * winning node's relative path (which embeds its node-specific subdir) against peer dirs, so
     * the candidate never existed and the fallback silently did nothing. The current
     * implementation walks each peer directory and matches by parsed block number, which is what
     * this test verifies.
     */
    @Test
    void findsCompleteBlockInPeerWithDifferentNodeSubdir(@TempDir final Path tmp) throws IOException {
        final var node0BlockStreams = Files.createDirectories(tmp.resolve("node0/blockStreams"));
        final var node1BlockStreams = Files.createDirectories(tmp.resolve("node1/blockStreams"));
        final var node0BlockDir = Files.createDirectories(node0BlockStreams.resolve("block-11.12.3"));
        final var node1BlockDir = Files.createDirectories(node1BlockStreams.resolve("block-11.12.4"));

        // Node 0 (the "winner") has an incomplete copy — no proof.
        writeBlockFile(node0BlockDir, BLOCK_NUMBER, blockWithoutProof());
        // Node 1 (the peer) has the complete copy — but under a different subdir name.
        writeBlockFile(node1BlockDir, BLOCK_NUMBER, blockWithProof());

        // Mirrors the caller: relativize the winning block's path against node 0's blockStreams
        // dir, which yields a path that embeds node 0's block-<...> subdir name.
        final var winningBlockPath = node0BlockDir.resolve(BLOCK_NUMBER + ".blk.gz");
        final var relativePath = node0BlockStreams.relativize(winningBlockPath);

        final var found = findCompleteBlockIn(List.of(node1BlockStreams), relativePath);

        assertNotNull(found, "peer's complete copy should be found despite differing subdir name");
        assertEquals(3, found.items().size(), "peer's copy should be the complete 3-item block");
        assertEquals(BLOCK_NUMBER, found.items().get(2).blockProofOrThrow().block());
    }

    @Test
    void returnsNullWhenNoPeerHasCompleteCopy(@TempDir final Path tmp) throws IOException {
        final var node1BlockStreams = Files.createDirectories(tmp.resolve("node1/blockStreams"));
        final var node1BlockDir = Files.createDirectories(node1BlockStreams.resolve("block-11.12.4"));
        writeBlockFile(node1BlockDir, BLOCK_NUMBER, blockWithoutProof());

        final var relativePath = Path.of("block-11.12.3", BLOCK_NUMBER + ".blk.gz");

        assertNull(findCompleteBlockIn(List.of(node1BlockStreams), relativePath));
    }

    /**
     * Covers the outer-loop traversal: the first peer dir has no matching block, so the method
     * must keep walking and find the complete copy in the second peer dir.
     */
    @Test
    void walksMultiplePeerDirsUntilCompleteCopyFound(@TempDir final Path tmp) throws IOException {
        final var peer1Streams = Files.createDirectories(tmp.resolve("peer1/blockStreams"));
        final var peer2Streams = Files.createDirectories(tmp.resolve("peer2/blockStreams"));
        final var peer2BlockDir = Files.createDirectories(peer2Streams.resolve("block-11.12.5"));
        // peer1 has no blocks at all; peer2 has the complete copy.
        writeBlockFile(peer2BlockDir, BLOCK_NUMBER, blockWithProof());

        final var relativePath = Path.of("block-11.12.3", BLOCK_NUMBER + ".blk.gz");
        final var found = findCompleteBlockIn(List.of(peer1Streams, peer2Streams), relativePath);

        assertNotNull(found, "should keep walking past empty peer dirs and find the later complete copy");
        assertEquals(BLOCK_NUMBER, found.items().get(2).blockProofOrThrow().block());
    }

    @Test
    void returnsNullWhenRelativePathHasNoBlockNumber(@TempDir final Path tmp) {
        // The early-return on extractBlockNumber == -1 fires before any I/O, so the peer dir
        // is never actually walked — passing a @TempDir keeps the test self-contained.
        assertNull(findCompleteBlockIn(List.of(tmp), Path.of("not-a-block-file")));
    }

    @Test
    void diagnosticDumpUsesNewCompressedFormatAndMarker(@TempDir final Path dir) throws Exception {
        final var write = StreamValidationOp.class.getDeclaredMethod("writeBlockFileToDisk", Path.class, Block.class);
        write.setAccessible(true);
        write.invoke(null, dir, blockWithProof());
        final var blockPath = dir.resolve("0000000000000000042.blk.zstd");
        assertEquals(blockWithProof(), BlockStreamAccess.blockFrom(blockPath));
        assertEquals(0L, Files.size(dir.resolve("0000000000000000042.mf")));
    }

    @Test
    void scansNewPendingFormatAndRequiresProofMetadata(@TempDir final Path dir) throws Exception {
        final var spec = mock(HapiSpec.class);
        final var node = mock(HederaNode.class);
        when(spec.getNetworkNodes()).thenReturn(List.of(node));
        when(node.getExternalPath(BLOCK_STREAMS_PARENT_DIR)).thenReturn(dir);
        final var pending = dir.resolve("0000000000000000042.pnd.zstd");
        try (final var out = BlockFileUtils.newZstdOutputStream(Files.newOutputStream(pending))) {
            out.write(Block.PROTOBUF.toBytes(blockWithProof()).toByteArray());
        }
        final var read = StreamValidationOp.class.getDeclaredMethod("readPendingBlocksFromDisk", HapiSpec.class);
        read.setAccessible(true);
        assertEquals(List.of(), read.invoke(null, spec));
        Files.writeString(dir.resolve("0000000000000000042.pnd.json"), "{}");
        assertEquals(List.of(blockWithProof()), read.invoke(null, spec));
    }

    @Test
    void choosesPendingSnapshotByProofMetadataTimeAcrossNamingWidths(@TempDir final Path dir) throws Exception {
        for (final var newerZstd : new boolean[] {true, false}) {
            final var snapshotDir = Files.createDirectory(dir.resolve(Boolean.toString(newerZstd)));
            final var legacy = writePendingSnapshot(
                    snapshotDir,
                    "000000000000000000000000000000000042.pnd.gz",
                    blockWithoutProof(),
                    newerZstd ? 1_000L : 2_000L);
            final var current = writePendingSnapshot(
                    snapshotDir, "0000000000000000042.pnd.zstd", blockWithProof(), newerZstd ? 2_000L : 1_000L);
            // The block payload's timestamp must not override its paired proof metadata's timestamp.
            Files.setLastModifiedTime(legacy, FileTime.fromMillis(newerZstd ? 4_000L : 3_000L));
            Files.setLastModifiedTime(current, FileTime.fromMillis(newerZstd ? 3_000L : 4_000L));
            assertEquals(
                    List.of(newerZstd ? blockWithProof() : blockWithoutProof()), readPendingSnapshots(snapshotDir));
        }
    }

    @Test
    void breaksPendingSnapshotTimeTiesByPath(@TempDir final Path dir) throws Exception {
        // Create the lexicographically greatest path first so traversal order cannot define the winner.
        writePendingSnapshot(dir, "0000000000000000042.pnd.zstd", blockWithProof(), 1_000L);
        writePendingSnapshot(dir, "000000000000000000000000000000000042.pnd.gz", blockWithoutProof(), 1_000L);
        assertEquals(List.of(blockWithProof()), readPendingSnapshots(dir));
    }

    @Test
    void diskReadPrefersSameNodeMarkedCompleteInEitherCreationOrder(@TempDir final Path dir) throws Exception {
        for (final var pendingFirst : new boolean[] {true, false}) {
            final var streams = Files.createDirectories(dir.resolve(Boolean.toString(pendingFirst)));
            final var blockDir = Files.createDirectory(streams.resolve("block-11.12.3"));
            final var completeName = "0000000000000000042.blk.zstd";
            if (!pendingFirst) {
                writeBlockFile(blockDir, completeName, blockWithProof());
            }
            writePendingSnapshot(blockDir, "000000000000000000000000000000000042.pnd.gz", blockWithoutProof(), 2_000L);
            if (pendingFirst) {
                writeBlockFile(blockDir, completeName, blockWithProof());
            }

            assertEquals(List.of(blockWithProof()), readDiskBlocks(streams).active());
            assertEquals(List.of(), readPendingSnapshots(streams));
        }
    }

    @Test
    void diskReadChoosesPendingSnapshotByNewestProofMetadata(@TempDir final Path dir) throws Exception {
        for (final var newerZstd : new boolean[] {true, false}) {
            final var streams = Files.createDirectory(dir.resolve(Boolean.toString(newerZstd)));
            final var legacy = writePendingSnapshot(
                    streams,
                    "000000000000000000000000000000000042.pnd.gz",
                    blockWithoutProof(),
                    newerZstd ? 1_000L : 2_000L);
            final var current = writePendingSnapshot(
                    streams, "0000000000000000042.pnd.zstd", blockWithProof(), newerZstd ? 2_000L : 1_000L);
            Files.setLastModifiedTime(legacy, FileTime.fromMillis(newerZstd ? 4_000L : 3_000L));
            Files.setLastModifiedTime(current, FileTime.fromMillis(newerZstd ? 3_000L : 4_000L));

            assertEquals(
                    List.of(newerZstd ? blockWithProof() : blockWithoutProof()),
                    readDiskBlocks(streams).active());
        }
    }

    @Test
    void diskReadSelectsArchiveIndependentlyAndKeepsOnlyBlocksBelowActiveRange(@TempDir final Path dir)
            throws Exception {
        final var streams = Files.createDirectory(dir.resolve("blockStreams"));
        final var archive = Files.createDirectory(dir.resolve("blockStreams-preview-archive"));
        writeBlockFile(streams, "0000000000000000042.blk.zstd", blockWithProof());
        writePendingSnapshot(archive, "000000000000000000000000000000000041.pnd.gz", blockWithoutProof(41L), 2_000L);
        writeBlockFile(archive, "0000000000000000041.blk.zstd", blockWithProof(41L));
        writeBlockFile(archive, BLOCK_NUMBER, blockWithoutProof());
        writeBlockFile(archive, 43L, blockWithProof(43L));

        final var blocks = readDiskBlocks(streams);
        assertEquals(List.of(blockWithProof()), blocks.active());
        assertEquals(List.of(blockWithProof(41L)), blocks.archived());
    }

    private static StreamValidationOp.DiskBlocks readDiskBlocks(final Path dir) throws Exception {
        final var spec = mock(HapiSpec.class);
        final var node = mock(HederaNode.class);
        when(spec.getNetworkNodes()).thenReturn(List.of(node));
        when(node.getExternalPath(BLOCK_STREAMS_PARENT_DIR)).thenReturn(dir);
        final var read = StreamValidationOp.class.getDeclaredMethod("readBlocksFromDisk", HapiSpec.class);
        read.setAccessible(true);
        final var blocks = (Optional<?>) read.invoke(null, spec);
        return assertInstanceOf(StreamValidationOp.DiskBlocks.class, blocks.orElseThrow());
    }

    private static Path writePendingSnapshot(
            final Path dir, final String name, final Block block, final long proofModifiedMillis) throws IOException {
        final var path = dir.resolve(name);
        try (final var out = name.endsWith(".zstd")
                ? BlockFileUtils.newZstdOutputStream(Files.newOutputStream(path))
                : new GZIPOutputStream(Files.newOutputStream(path))) {
            out.write(Block.PROTOBUF.toBytes(block).toByteArray());
        }
        final var proofPath = BlockFileUtils.pendingProofPathFor(path);
        Files.writeString(proofPath, "{}");
        Files.setLastModifiedTime(proofPath, FileTime.fromMillis(proofModifiedMillis));
        return path;
    }

    private static List<?> readPendingSnapshots(final Path dir) throws Exception {
        final var spec = mock(HapiSpec.class);
        final var node = mock(HederaNode.class);
        when(spec.getNetworkNodes()).thenReturn(List.of(node));
        when(node.getExternalPath(BLOCK_STREAMS_PARENT_DIR)).thenReturn(dir);
        final var read = StreamValidationOp.class.getDeclaredMethod("readPendingBlocksFromDisk", HapiSpec.class);
        read.setAccessible(true);
        return (List<?>) read.invoke(null, spec);
    }

    private static Block blockWithProof() {
        return blockWithProof(BLOCK_NUMBER);
    }

    private static Block blockWithProof(final long blockNumber) {
        return Block.newBuilder()
                .items(List.of(
                        BlockItem.newBuilder()
                                .blockHeader(BlockHeader.newBuilder()
                                        .number(blockNumber)
                                        .build())
                                .build(),
                        BlockItem.newBuilder()
                                .blockFooter(BlockFooter.newBuilder()
                                        .previousBlockRootHash(ROOT_HASH)
                                        .build())
                                .build(),
                        BlockItem.newBuilder()
                                .blockProof(BlockProof.newBuilder()
                                        .block(blockNumber)
                                        .build())
                                .build()))
                .build();
    }

    private static Block blockWithoutProof() {
        return blockWithoutProof(BLOCK_NUMBER);
    }

    private static Block blockWithoutProof(final long blockNumber) {
        return Block.newBuilder()
                .items(List.of(
                        BlockItem.newBuilder()
                                .blockHeader(BlockHeader.newBuilder()
                                        .number(blockNumber)
                                        .build())
                                .build(),
                        BlockItem.newBuilder()
                                .blockFooter(BlockFooter.newBuilder()
                                        .previousBlockRootHash(ROOT_HASH)
                                        .build())
                                .build()))
                .build();
    }

    private static void writeBlockFile(final Path dir, final long blockNumber, final Block block) throws IOException {
        writeBlockFile(dir, blockNumber + ".blk.gz", block);
    }

    private static void writeBlockFile(final Path dir, final String name, final Block block) throws IOException {
        final var blockPath = dir.resolve(name);
        final byte[] bytes = Block.PROTOBUF.toBytes(block).toByteArray();
        try (final var out = name.endsWith(".zstd")
                ? BlockFileUtils.newZstdOutputStream(Files.newOutputStream(blockPath))
                : new GZIPOutputStream(Files.newOutputStream(blockPath))) {
            out.write(bytes);
        }
        // Marker file is required for BlockStreamAccess.isBlockFile(p, true) to return true.
        Files.createFile(BlockFileUtils.markerPathFor(blockPath));
    }
}
