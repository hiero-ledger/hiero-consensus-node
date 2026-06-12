// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RedactingEventHashBlockStreamValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void wrbOnlyBlocksDoNotCrash() {
        final var pcesData = new PcesEventHashReader.PcesData(Set.of(), Map.of());
        final var validator = new RedactingEventHashBlockStreamValidator(tempDir, pcesData);
        final var blocks = List.of(BlockTestHelpers.wrbBlock(1), BlockTestHelpers.wrbBlock(2));
        assertDoesNotThrow(() -> validator.validateBlocks(blocks));
    }

    @Test
    void normalBlocksStillValidate() {
        final var pcesData = new PcesEventHashReader.PcesData(Set.of(), Map.of());
        final var validator = new RedactingEventHashBlockStreamValidator(tempDir, pcesData);
        final var blocks = List.of(BlockTestHelpers.normalBlock(1));
        assertDoesNotThrow(() -> validator.validateBlocks(blocks));
    }
}
