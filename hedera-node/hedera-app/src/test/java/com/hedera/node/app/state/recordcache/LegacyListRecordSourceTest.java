// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SCHEDULE_ALREADY_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.node.app.state.SingleTransactionRecord;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LegacyListRecordSourceTest {
    private static final TransactionRecord FIRST_RECORD = TransactionRecord.newBuilder()
            .receipt(TransactionReceipt.newBuilder()
                    .status(SCHEDULE_ALREADY_DELETED)
                    .build())
            .transactionID(TransactionID.newBuilder().nonce(1).build())
            .memo("FIRST")
            .build();
    private static final TransactionRecord SECOND_RECORD = TransactionRecord.newBuilder()
            .receipt(TransactionReceipt.newBuilder().status(SUCCESS).build())
            .transactionID(TransactionID.newBuilder().nonce(2).build())
            .memo("SECOND")
            .build();
    private static final SingleTransactionRecord FIRST_ITEM = new SingleTransactionRecord(
            Transaction.DEFAULT, FIRST_RECORD, List.of(), new SingleTransactionRecord.TransactionOutputs(null));
    private static final SingleTransactionRecord SECOND_ITEM = new SingleTransactionRecord(
            Transaction.DEFAULT, SECOND_RECORD, List.of(), new SingleTransactionRecord.TransactionOutputs(null));
    private static final List<SingleTransactionRecord> ITEMS = List.of(FIRST_ITEM, SECOND_ITEM);
    private static final List<RecordSource.IdentifiedReceipt> RECEIPTS = List.of(
            new RecordSource.IdentifiedReceipt(FIRST_RECORD.transactionIDOrThrow(), FIRST_RECORD.receiptOrThrow()),
            new RecordSource.IdentifiedReceipt(SECOND_RECORD.transactionIDOrThrow(), SECOND_RECORD.receiptOrThrow()));

    private final LegacyListRecordSource subject = new LegacyListRecordSource(ITEMS, RECEIPTS);

    @Mock
    private Consumer<TransactionRecord> recordAction;

    @Test
    void consumerGetsAllRecords() {
        subject.forEachTxnRecord(recordAction);

        verify(recordAction).accept(FIRST_RECORD);
        verify(recordAction).accept(SECOND_RECORD);
    }

    @Test
    void getsPresentReceiptsAndThrowsOtherwise() {
        assertEquals(FIRST_RECORD.receiptOrThrow(), subject.receiptOf(FIRST_RECORD.transactionIDOrThrow()));
        assertEquals(SECOND_RECORD.receiptOrThrow(), subject.receiptOf(SECOND_RECORD.transactionIDOrThrow()));
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.receiptOf(TransactionID.newBuilder().nonce(3).build()));
    }

    @Test
    void getsChildReceipts() {
        assertEquals(
                List.of(FIRST_RECORD.receiptOrThrow(), SECOND_RECORD.receiptOrThrow()),
                subject.childReceiptsOf(TransactionID.DEFAULT));
        assertEquals(
                List.of(),
                subject.childReceiptsOf(TransactionID.newBuilder().nonce(3).build()));
    }
}
