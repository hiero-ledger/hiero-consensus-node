// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows.record;

import static com.hedera.node.app.spi.workflows.record.StreamBuilder.TransactionCustomizer.SUPPRESSING_TRANSACTION_CUSTOMIZER;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.base.Transaction;
import org.junit.jupiter.api.Test;

class ExternalizedRecordCustomizerTest {
    @Test
    void suppressionIsOffByDefault() {
        assertFalse(StreamBuilder.TransactionCustomizer.NOOP_TRANSACTION_CUSTOMIZER.isSuppressed());
    }

    @Test
    void suppressingCustomizerAsExpected() {
        assertTrue(SUPPRESSING_TRANSACTION_CUSTOMIZER.isSuppressed());
        assertThrows(
                UnsupportedOperationException.class,
                () -> SUPPRESSING_TRANSACTION_CUSTOMIZER.apply(Transaction.DEFAULT));
    }
}
