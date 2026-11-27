// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.pauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HapiSpecWaitUntilNextBlockTest {
    @Test
    void recognizesNewBlocksAndMarkersOfEitherWidth(@TempDir final Path dir) throws Exception {
        final var op = new HapiSpecWaitUntilNextBlock();
        Files.createFile(dir.resolve("0000000000000000042.blk.zstd"));
        final var latest = HapiSpecWaitUntilNextBlock.class.getDeclaredMethod("findLatestBlockNumber", Path.class);
        latest.setAccessible(true);
        assertEquals(42L, latest.invoke(op, dir));
        final var complete =
                HapiSpecWaitUntilNextBlock.class.getDeclaredMethod("isBlockComplete", Path.class, long.class);
        complete.setAccessible(true);
        assertFalse((boolean) complete.invoke(op, dir, 42L));
        Files.createFile(dir.resolve("0000000000000000042.mf"));
        assertTrue((boolean) complete.invoke(op, dir, 42L));
        Files.delete(dir.resolve("0000000000000000042.mf"));
        Files.createFile(dir.resolve("000000000000000000000000000000000042.mf"));
        assertTrue((boolean) complete.invoke(op, dir, 42L));
        assertFalse((boolean) complete.invoke(op, dir, 4L));
    }
}
