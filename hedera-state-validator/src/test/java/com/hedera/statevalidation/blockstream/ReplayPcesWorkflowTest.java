// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayPcesWorkflowTest {
    @Test
    void waitsForMarkersAcrossAllSupportedFormats(@TempDir final Path dir) throws Exception {
        final var nodeDir = Files.createDirectories(dir.resolve("block-0.0.3"));
        for (final var suffix : new String[] {".blk.zstd", ".blk.gz", ".blk"}) {
            final var block = Files.createFile(nodeDir.resolve("0000000000000000042" + suffix));
            assertEquals(Optional.of(block.toString()), firstUnfinalized(dir));
            final var marker = Files.createFile(nodeDir.resolve("0000000000000000042.mf"));
            assertTrue(firstUnfinalized(dir).isEmpty(), suffix);
            Files.delete(marker);
            Files.delete(block);
        }
    }

    @Test
    void pendingBlocksAndProofsPreventFinalization(@TempDir final Path dir) throws Exception {
        for (final var suffix : new String[] {".pnd.zstd", ".pnd.gz", ".pnd", ".pnd.json"}) {
            final var pending = Files.createFile(dir.resolve("0000000000000000042" + suffix));
            assertEquals(Optional.of(pending.toString()), firstUnfinalized(dir), suffix);
            Files.delete(pending);
        }
    }

    @Test
    void ignoresRetainedPendingPairAfterSameDirectoryBlockIsFinalized(@TempDir final Path dir) throws Exception {
        createRetainedPendingPair(dir);
        createFinalizedBlock(dir);
        assertTrue(firstUnfinalized(dir).isEmpty());
    }

    @Test
    void markerWithoutCompletePayloadDoesNotFinalizeRetainedPendingPair(@TempDir final Path dir) throws Exception {
        createRetainedPendingPair(dir);
        Files.createFile(dir.resolve("0000000000000000042.mf"));
        assertTrue(firstUnfinalized(dir).isPresent());
    }

    @Test
    void finalizedBlockAfterAccountRenumberingFinalizesRetainedPendingPair(@TempDir final Path dir) throws Exception {
        createRetainedPendingPair(Files.createDirectory(dir.resolve("block-0.0.3")));
        createFinalizedBlock(Files.createDirectory(dir.resolve("block-0.0.4")));
        assertTrue(firstUnfinalized(dir).isEmpty());
    }

    @Test
    void markerInAnotherAccountDirectoryDoesNotFinalizePayloadOrPendingPair(@TempDir final Path dir) throws Exception {
        final var oldAccountDir = Files.createDirectory(dir.resolve("block-0.0.3"));
        final var currentAccountDir = Files.createDirectory(dir.resolve("block-0.0.4"));
        createRetainedPendingPair(oldAccountDir);
        Files.createFile(currentAccountDir.resolve("0000000000000000042.blk.zstd"));
        Files.createFile(oldAccountDir.resolve("0000000000000000042.mf"));
        assertTrue(firstUnfinalized(dir).isPresent());
    }

    @Test
    void finalizedDifferentBlockDoesNotHideRetainedPendingPair(@TempDir final Path dir) throws Exception {
        createRetainedPendingPair(Files.createDirectory(dir.resolve("block-0.0.3")));
        final var currentAccountDir = Files.createDirectory(dir.resolve("block-0.0.4"));
        Files.createFile(currentAccountDir.resolve("0000000000000000043.blk.zstd"));
        Files.createFile(currentAccountDir.resolve("0000000000000000043.mf"));
        assertTrue(firstUnfinalized(dir).isPresent());
    }

    @Test
    void finalizedBlockInSiblingReplayOutputDoesNotHideRetainedPendingPair(@TempDir final Path dir) throws Exception {
        final var replayRoot = Files.createDirectories(dir.resolve("replay/blockStreams"));
        createRetainedPendingPair(Files.createDirectory(replayRoot.resolve("block-0.0.3")));
        createFinalizedBlock(Files.createDirectories(dir.resolve("other-replay/blockStreams/block-0.0.4")));
        assertTrue(firstUnfinalized(replayRoot).isPresent());
    }

    @Test
    void invalidBlockNumbersDoNotFinalizePendingFiles(@TempDir final Path dir) throws Exception {
        final var oldAccountDir = Files.createDirectory(dir.resolve("block-0.0.3"));
        final var currentAccountDir = Files.createDirectory(dir.resolve("block-0.0.4"));
        Files.createFile(oldAccountDir.resolve("invalid.pnd.gz"));
        Files.createFile(oldAccountDir.resolve("invalid.pnd.json"));
        Files.createFile(currentAccountDir.resolve("invalid.blk.zstd"));
        Files.createFile(currentAccountDir.resolve("invalid.mf"));
        assertTrue(firstUnfinalized(dir).isPresent());
    }

    @Test
    void unmarkedDuplicateCompletePayloadStillPreventsFinalization(@TempDir final Path dir) throws Exception {
        createFinalizedBlock(dir);
        final var unmarked = Files.createFile(dir.resolve("000000000000000000000000000000000042.blk.gz"));
        assertEquals(Optional.of(unmarked.toString()), firstUnfinalized(dir));
    }

    @Test
    void markerDirectoryDoesNotFinalizeBlockOrPendingPair(@TempDir final Path dir) throws Exception {
        createRetainedPendingPair(dir);
        Files.createFile(dir.resolve("0000000000000000042.blk.zstd"));
        Files.createDirectory(dir.resolve("0000000000000000042.mf"));
        assertTrue(firstUnfinalized(dir).isPresent());
    }

    private static void createRetainedPendingPair(final Path dir) throws Exception {
        Files.createFile(dir.resolve("000000000000000000000000000000000042.pnd.gz"));
        Files.createFile(dir.resolve("000000000000000000000000000000000042.pnd.json"));
    }

    private static void createFinalizedBlock(final Path dir) throws Exception {
        Files.createFile(dir.resolve("0000000000000000042.blk.zstd"));
        Files.createFile(dir.resolve("0000000000000000042.mf"));
    }

    @SuppressWarnings("unchecked")
    private static Optional<String> firstUnfinalized(final Path dir) throws Exception {
        final var method = ReplayPcesWorkflow.class.getDeclaredMethod("firstUnfinalized", Path.class);
        method.setAccessible(true);
        return (Optional<String>) method.invoke(null, dir);
    }
}
