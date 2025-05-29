// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.CHILD;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.TransactionCustomizer.NOOP_TRANSACTION_CUSTOMIZER;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PairedStreamBuilderTest {
    @Test
    void doesNotDelegateSlotUsagesToRecordBuilder() {
        final var recordBuilder = new RecordStreamBuilder(REVERSIBLE, NOOP_TRANSACTION_CUSTOMIZER, CHILD);
        assertThrows(UnsupportedOperationException.class, () -> recordBuilder.addContractSlotUsages(List.of()));
        final var pairedBuilder = new PairedStreamBuilder(REVERSIBLE, NOOP_TRANSACTION_CUSTOMIZER, CHILD);
        assertDoesNotThrow(() -> pairedBuilder.addContractSlotUsages(List.of()));
    }
}
