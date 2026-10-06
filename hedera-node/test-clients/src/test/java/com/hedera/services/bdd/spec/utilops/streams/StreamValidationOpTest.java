// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.hedera.services.bdd.spec.utilops.streams.StreamValidationOp.findCompleteBlockIn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.output.BlockFooter;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    private static Block blockWithProof() {
        return Block.newBuilder()
                .items(List.of(
                        BlockItem.newBuilder()
                                .blockHeader(BlockHeader.newBuilder()
                                        .number(BLOCK_NUMBER)
                                        .build())
                                .build(),
                        BlockItem.newBuilder()
                                .blockFooter(BlockFooter.newBuilder()
                                        .previousBlockRootHash(ROOT_HASH)
                                        .build())
                                .build(),
                        BlockItem.newBuilder()
                                .blockProof(BlockProof.newBuilder()
                                        .block(BLOCK_NUMBER)
                                        .build())
                                .build()))
                .build();
    }

    private static Block blockWithoutProof() {
        return Block.newBuilder()
                .items(List.of(
                        BlockItem.newBuilder()
                                .blockHeader(BlockHeader.newBuilder()
                                        .number(BLOCK_NUMBER)
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
        final var blockPath = dir.resolve(blockNumber + ".blk.gz");
        final byte[] bytes = Block.PROTOBUF.toBytes(block).toByteArray();
        try (final var out = new GZIPOutputStream(Files.newOutputStream(blockPath))) {
            out.write(bytes);
        }
        // Marker file is required for BlockStreamAccess.isBlockFile(p, true) to return true.
        Files.createFile(dir.resolve(blockNumber + ".mf"));
    }
}
