// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TransactionPoolTests {

    @Test
    void addTransactionTest() {
        final List<Bytes> transactionList = new ArrayList<>();
        final TransactionPoolNexus transactionPoolNexus = mock(TransactionPoolNexus.class);
        when(transactionPoolNexus.submitTransaction(any(), anyBoolean())).thenAnswer(invocation -> {
            final Bytes transaction = invocation.getArgument(0);
            final boolean isPriority = invocation.getArgument(1);
            assertTrue(isPriority);
            transactionList.add(transaction);
            return true;
        });

        final TransactionPool transactionPool = new DefaultTransactionPool(transactionPoolNexus);
        final StateSignatureTransaction signatureTransaction = StateSignatureTransaction.newBuilder()
                .round(1)
                .signature(Bytes.EMPTY)
                .build();
        final Bytes signatureBytes = StateSignatureTransaction.PROTOBUF.toBytes(signatureTransaction);

        transactionPool.submitSystemTransaction(signatureBytes);
        assertEquals(1, transactionList.size());
        assertEquals(signatureBytes, transactionList.getFirst());
    }

    @Test
    void clearTest() {
        final TransactionPoolNexus transactionPoolNexus = mock(TransactionPoolNexus.class);
        final AtomicBoolean clearCalled = new AtomicBoolean(false);

        doAnswer(invocation -> {
                    clearCalled.set(true);
                    return null;
                })
                .when(transactionPoolNexus)
                .clear();

        final TransactionPool transactionPool = new DefaultTransactionPool(transactionPoolNexus);
        transactionPool.clear();

        assertTrue(clearCalled.get());
    }
}
