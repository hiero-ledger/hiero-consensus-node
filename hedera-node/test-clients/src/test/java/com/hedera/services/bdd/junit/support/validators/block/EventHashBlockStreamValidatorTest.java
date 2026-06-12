// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;
import org.junit.jupiter.api.Test;

class EventHashBlockStreamValidatorTest {

    @Test
    void wrbOnlyBlocksDoNotCrash() {
        final var validator = new EventHashBlockStreamValidator();
        final var blocks = List.of(BlockTestHelpers.wrbBlock(1), BlockTestHelpers.wrbBlock(2));
        assertDoesNotThrow(() -> validator.validateBlocks(blocks));
    }

    @Test
    void normalBlocksStillValidate() {
        final var validator = new EventHashBlockStreamValidator();
        final var blocks = List.of(BlockTestHelpers.normalBlock(1));
        assertDoesNotThrow(() -> validator.validateBlocks(blocks));
    }
}
