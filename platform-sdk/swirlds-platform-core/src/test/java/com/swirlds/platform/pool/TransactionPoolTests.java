/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.pool;

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
