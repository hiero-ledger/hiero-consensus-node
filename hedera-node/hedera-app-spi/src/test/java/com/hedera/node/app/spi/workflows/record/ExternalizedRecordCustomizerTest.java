// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows.record;

import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.SUPPRESSING_SIGNED_TX_CUSTOMIZER;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.transaction.SignedTransaction;
import org.junit.jupiter.api.Test;

class ExternalizedRecordCustomizerTest {
    @Test
    void suppressionIsOffByDefault() {
        assertFalse(StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER.isSuppressed());
    }

    @Test
    void suppressingCustomizerAsExpected() {
        assertTrue(SUPPRESSING_SIGNED_TX_CUSTOMIZER.isSuppressed());
        assertThrows(
                UnsupportedOperationException.class,
                () -> SUPPRESSING_SIGNED_TX_CUSTOMIZER.apply(SignedTransaction.DEFAULT));
    }
}
