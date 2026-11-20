// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WrapsFreeBlockSignaturesValidatorTest {
    @Test
    void acceptsCompleteBlocksAndProtobufButSkipsPendingAndIncompleteFiles() throws Exception {
        final var candidate =
                WrapsFreeBlockSignaturesValidator.class.getDeclaredMethod("candidateBlockNumber", Path.class);
        candidate.setAccessible(true);
        for (final var suffix : new String[] {".blk.zstd", ".blk.gz", ".blk", ".pb", ".pb.gz"}) {
            assertEquals(42L, candidate.invoke(null, Path.of("0000000000000000042" + suffix)), suffix);
        }
        for (final var suffix : new String[] {".pnd.zstd", ".pnd.gz", ".pnd", ".blk.zstd.tmp", ".mf", ".pnd.json"}) {
            assertEquals(-1L, candidate.invoke(null, Path.of("0000000000000000042" + suffix)), suffix);
        }
    }
}
