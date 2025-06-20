// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache.schemas;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.state.recordcache.schemas.V0490RecordCacheSchema.TXN_RECORD_QUEUE;
import static com.hedera.node.app.state.recordcache.schemas.V0540RecordCacheSchema.TXN_RECEIPT_QUEUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.recordcache.TransactionReceiptEntries;
import com.hedera.hapi.node.state.recordcache.TransactionRecordEntry;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableStates;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class V0540RecordCacheSchemaTest {
    @Mock(strictness = LENIENT)
    private MigrationContext mockCtx;

    @Mock(strictness = LENIENT)
    private ReadableQueueState<TransactionRecordEntry> mockRecordQueue;

    @Mock(strictness = LENIENT)
    private WritableQueueState<TransactionReceiptEntries> mockReceiptQueue;

    @Mock(strictness = LENIENT)
    private ReadableStates mockReadableStates;

    @Mock(strictness = LENIENT)
    private WritableStates mockWritableStates;

    private V0540RecordCacheSchema schema;

    @BeforeEach
    void setUp() {
        schema = new V0540RecordCacheSchema();
        when(mockCtx.previousStates()).thenReturn(mockReadableStates);
        when(mockCtx.newStates()).thenReturn(mockWritableStates);
        when(mockReadableStates.<TransactionRecordEntry>getQueue(TXN_RECORD_QUEUE))
                .thenReturn(mockRecordQueue);
        when(mockWritableStates.<TransactionReceiptEntries>getQueue(TXN_RECEIPT_QUEUE))
                .thenReturn(mockReceiptQueue);
    }

    @Test
    void testVersion() {
        assertEquals(0, schema.getVersion().major());
        assertEquals(54, schema.getVersion().minor());
        assertEquals(0, schema.getVersion().patch());
    }

    @Test
    void testStatesToCreate() {
        Set<StateDefinition> statesToCreate = schema.statesToCreate();
        assertNotNull(statesToCreate);
        assertEquals(1, statesToCreate.size());
        assertTrue(statesToCreate.stream().anyMatch(state -> state.stateKey().equals(TXN_RECEIPT_QUEUE)));
    }

    @Test
    void testStatesToRemove() {
        Set<String> statesToRemove = schema.statesToRemove();
        assertNotNull(statesToRemove);
        assertEquals(1, statesToRemove.size());
        assertTrue(statesToRemove.contains(TXN_RECORD_QUEUE));
    }

    @Test
    void testMigration() {
        List<TransactionRecordEntry> records = new ArrayList<>();
        final var consensusTimestamp = Instant.ofEpochSecond(123456789L).plusNanos(1000);
        final var payer = AccountID.newBuilder().accountNum(1001).build();
        final var nodeAccountId = AccountID.newBuilder().accountNum(3).build();
        final var receipt = TransactionReceipt.newBuilder()
                .accountID(nodeAccountId)
                .status(ResponseCodeEnum.UNKNOWN)
                .build();
        final var rec1 = TransactionRecord.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .transactionValidStart(Timestamp.newBuilder()
                                .seconds(consensusTimestamp.getEpochSecond())
                                .nanos(consensusTimestamp.getNano()))
                        .accountID(payer)
                        .nonce(0)
                        .build())
                .receipt(receipt)
                .consensusTimestamp(asTimestamp(consensusTimestamp.plusMillis(1)))
                .build();
        records.add(TransactionRecordEntry.newBuilder()
                .payerAccountId(payer)
                .nodeId(1)
                .transactionRecord(rec1)
                .build());

        Iterator<TransactionRecordEntry> mockIterator = records.iterator();
        when(mockRecordQueue.iterator()).thenReturn(mockIterator);

        schema.migrate(mockCtx);

        ArgumentCaptor<TransactionReceiptEntries> captor = ArgumentCaptor.forClass(TransactionReceiptEntries.class);
        verify(mockReceiptQueue).add(captor.capture());

        TransactionReceiptEntries addedEntries = captor.getValue();
        assertNotNull(addedEntries);
        assertEquals(1, addedEntries.entries().size());

        final var firstReceipt = addedEntries.entries().getFirst();
        assertEquals(ResponseCodeEnum.UNKNOWN, firstReceipt.status());
        assertEquals(1, firstReceipt.nodeId());
        assertEquals(rec1.transactionID(), firstReceipt.transactionId());
    }
}
