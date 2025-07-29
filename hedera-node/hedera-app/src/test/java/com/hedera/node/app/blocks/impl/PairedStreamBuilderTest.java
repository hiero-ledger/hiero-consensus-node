// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.CHILD;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.block.stream.trace.ExecutedInitcode;
import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PairedStreamBuilderTest {
    private final RecordStreamBuilder recordBuilder =
            new RecordStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, CHILD);
    private final PairedStreamBuilder pairedBuilder =
            new PairedStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, CHILD);

    @Test
    void doesNotDelegateSlotUsagesToRecordBuilder() {
        assertThrows(UnsupportedOperationException.class, () -> recordBuilder.addContractSlotUsages(List.of()));
        assertDoesNotThrow(() -> pairedBuilder.addContractSlotUsages(List.of()));
    }

    @Test
    void doesNotDelegateActionsToRecordBuilder() {
        assertThrows(UnsupportedOperationException.class, () -> recordBuilder.addActions(List.of()));
        assertDoesNotThrow(() -> pairedBuilder.addActions(List.of()));
    }

    @Test
    void doesNotDelegateInitcodeToRecordBuilder() {
        assertThrows(UnsupportedOperationException.class, () -> recordBuilder.addInitcode(ExecutedInitcode.DEFAULT));
        assertDoesNotThrow(() -> pairedBuilder.addInitcode(ExecutedInitcode.DEFAULT));
    }
}
