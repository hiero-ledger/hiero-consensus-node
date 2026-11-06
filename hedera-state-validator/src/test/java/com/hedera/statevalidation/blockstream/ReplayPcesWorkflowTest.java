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

    @SuppressWarnings("unchecked")
    private static Optional<String> firstUnfinalized(final Path dir) throws Exception {
        final var method = ReplayPcesWorkflow.class.getDeclaredMethod("firstUnfinalized", Path.class);
        method.setAccessible(true);
        return (Optional<String>) method.invoke(null, dir);
    }
}
