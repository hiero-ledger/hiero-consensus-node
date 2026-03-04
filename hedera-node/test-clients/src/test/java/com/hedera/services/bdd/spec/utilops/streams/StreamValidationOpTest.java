// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for <a href="https://github.com/hiero-ledger/hiero-consensus-node/issues/23858">#23858</a>.
 *
 * <p>Verifies that {@link StreamValidationOp#readBlocksFromNodeBlockPaths} resolves each
 * node's block path to its parent directory, so blocks spread across multiple
 * {@code block-X.Y.Z} subdirectories (due to account ID changes during node rotation)
 * are all discovered.
 */
class StreamValidationOpTest {

    @Test
    @DisplayName("Finds all blocks across multiple account-ID subdirectories")
    void findsAllBlocksAcrossSubdirs(@TempDir Path tempDir) throws IOException {
        final var subDir1 = tempDir.resolve("block-0.0.3");
        final var subDir2 = tempDir.resolve("block-0.0.905");
        Files.createDirectories(subDir1);
        Files.createDirectories(subDir2);

        writeBlockFile(subDir1, 0);
        writeBlockFile(subDir1, 1);
        writeBlockFile(subDir2, 2);
        writeBlockFile(subDir2, 3);

        // Pass subdirectory paths (as node.getExternalPath would return);
        // the method must resolve to parent to find blocks in sibling dirs
        final var result = StreamValidationOp.readBlocksFromNodeBlockPaths(List.of(subDir1, subDir2));

        assertTrue(result.isPresent());
        assertEquals(4, result.get().size());
    }

    @Test
    @DisplayName("Even a single node path discovers sibling subdirectory blocks via parent")
    void singleNodePathFindsAllSiblingBlocks(@TempDir Path tempDir) throws IOException {
        final var subDir1 = tempDir.resolve("block-0.0.3");
        final var subDir2 = tempDir.resolve("block-0.0.905");
        Files.createDirectories(subDir1);
        Files.createDirectories(subDir2);

        writeBlockFile(subDir1, 0);
        writeBlockFile(subDir2, 1);

        // Only one node path provided, but parent resolution finds both subdirs
        final var result = StreamValidationOp.readBlocksFromNodeBlockPaths(List.of(subDir1));

        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());
    }

    private static void writeBlockFile(Path dir, long blockNumber) throws IOException {
        final var now = Instant.now();
        final var block = Block.newBuilder()
                .items(List.of(BlockItem.newBuilder()
                        .blockHeader(BlockHeader.newBuilder()
                                .number(blockNumber)
                                .blockTimestamp(new Timestamp(now.getEpochSecond(), now.getNano()))
                                .softwareVersion(new SemanticVersion(1, 0, 0, null, null))
                                .build())
                        .build()))
                .build();
        Files.write(
                dir.resolve(blockNumber + ".blk"), Block.PROTOBUF.toBytes(block).toByteArray());
        // Marker file required by BlockStreamAccess.readBlocks()
        Files.createFile(dir.resolve(blockNumber + ".mf"));
    }
}
