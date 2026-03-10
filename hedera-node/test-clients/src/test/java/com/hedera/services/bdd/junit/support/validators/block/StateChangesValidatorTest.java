// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

class StateChangesValidatorTest {
    private static final int HINTS_SIGNATURE_LENGTH = 1632;
    private static final int COMPRESSED_WRAPS_PROOF_LENGTH = 704;
    private static final int AGGREGATE_SCHNORR_SIGNATURE_LENGTH = 192;

    @Test
    void detectsCompressedWrapsProofSignatureShape() {
        final var wrapsSignature =
                Bytes.wrap(new byte[HintsLibraryImpl.VK_LENGTH + HINTS_SIGNATURE_LENGTH + COMPRESSED_WRAPS_PROOF_LENGTH]);

        assertTrue(StateChangesValidator.hasCompressedWrapsProof(wrapsSignature));
    }

    @Test
    void rejectsAggregateSignatureShape() {
        final var aggregateSignature = Bytes.wrap(
                new byte[HintsLibraryImpl.VK_LENGTH + HINTS_SIGNATURE_LENGTH + AGGREGATE_SCHNORR_SIGNATURE_LENGTH]);

        assertFalse(StateChangesValidator.hasCompressedWrapsProof(aggregateSignature));
    }

    @Test
    void skipsWrapsAssertionWhenGloballyDisabled() {
        final var originalValue = StateChangesValidator.AT_LEAST_ONE_WRAPS_ASSERTION_ENABLED.get();
        StateChangesValidator.AT_LEAST_ONE_WRAPS_ASSERTION_ENABLED.set(false);

        try {
            assertFalse(StateChangesValidator.shouldAssertAtLeastOneWraps(true));
            assertFalse(StateChangesValidator.shouldAssertAtLeastOneWraps(false));
        } finally {
            StateChangesValidator.AT_LEAST_ONE_WRAPS_ASSERTION_ENABLED.set(originalValue);
        }
    }

    @Test
    void requiresWrapsAssertionWhenEnabledAndRequested() {
        final var originalValue = StateChangesValidator.AT_LEAST_ONE_WRAPS_ASSERTION_ENABLED.get();
        StateChangesValidator.AT_LEAST_ONE_WRAPS_ASSERTION_ENABLED.set(true);

        try {
            assertTrue(StateChangesValidator.shouldAssertAtLeastOneWraps(true));
            assertFalse(StateChangesValidator.shouldAssertAtLeastOneWraps(false));
        } finally {
            StateChangesValidator.AT_LEAST_ONE_WRAPS_ASSERTION_ENABLED.set(originalValue);
        }
    }
}
