// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.records;

import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNKNOWN;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecordCacheTest {
    private static final TransactionID USER_TXN_ID = TransactionID.newBuilder()
            .accountID(AccountID.newBuilder().accountNum(666L).build())
            .transactionValidStart(new Timestamp(1, 0))
            .scheduled(true)
            .build();

    @Test
    void constantsAsExpected() {
        assertThat(RecordCache.NODE_FAILURES).containsExactlyInAnyOrder(INVALID_PAYER_SIGNATURE, INVALID_NODE_ACCOUNT);
        assertThat(RecordCache.ReceiptSource.PENDING_RECEIPT)
                .isEqualTo(TransactionReceipt.newBuilder().status(UNKNOWN).build());
    }

    @Test
    void comparatorPutsAnyPossiblyUniqueReceiptFirst() {
        final var earlyNonUniqueRecord = TransactionRecord.newBuilder()
                .receipt(TransactionReceipt.newBuilder()
                        .status(INVALID_PAYER_SIGNATURE)
                        .build())
                .consensusTimestamp(new Timestamp(1234, 0))
                .build();
        final var latePossiblyUniqueRecord = TransactionRecord.newBuilder()
                .receipt(TransactionReceipt.newBuilder()
                        .status(DUPLICATE_TRANSACTION)
                        .build())
                .consensusTimestamp(new Timestamp(5678, 0))
                .build();
        assertThat(RecordCache.RECORD_COMPARATOR.compare(latePossiblyUniqueRecord, earlyNonUniqueRecord))
                .isLessThan(0);
    }

    @Test
    void twoPossiblyUniqueReceiptsAreOrderedByConsTime() {
        final var earlyUniqueRecord = TransactionRecord.newBuilder()
                .receipt(TransactionReceipt.newBuilder().status(SUCCESS).build())
                .consensusTimestamp(new Timestamp(1234, 0))
                .build();
        final var lateUniqueRecord = TransactionRecord.newBuilder()
                .receipt(TransactionReceipt.newBuilder()
                        .status(DUPLICATE_TRANSACTION)
                        .build())
                .consensusTimestamp(new Timestamp(5678, 0))
                .build();
        assertThat(RecordCache.RECORD_COMPARATOR.compare(lateUniqueRecord, earlyUniqueRecord))
                .isGreaterThan(0);
    }

    @Test
    void idsMatchEvenIfNonceDiffers() {
        assertThat(RecordCache.matchesExceptNonce(
                        USER_TXN_ID, USER_TXN_ID.copyBuilder().nonce(1).build()))
                .isTrue();
    }

    @Test
    void idsDontMatchIfAnythingButNonceDiffers() {
        assertThat(RecordCache.matchesExceptNonce(
                        USER_TXN_ID,
                        USER_TXN_ID
                                .copyBuilder()
                                .transactionValidStart(Timestamp.DEFAULT)
                                .build()))
                .isFalse();
        assertThat(RecordCache.matchesExceptNonce(
                        USER_TXN_ID,
                        USER_TXN_ID.copyBuilder().accountID(AccountID.DEFAULT).build()))
                .isFalse();
        assertThat(RecordCache.matchesExceptNonce(
                        USER_TXN_ID, USER_TXN_ID.copyBuilder().scheduled(false).build()))
                .isFalse();
    }

    @Test
    void childDetectionRequiresUserTxnIdAsParent() {
        assertThat(RecordCache.isChild(
                        USER_TXN_ID, USER_TXN_ID.copyBuilder().nonce(2).build()))
                .isTrue();
        assertThat(RecordCache.isChild(
                        USER_TXN_ID.copyBuilder().nonce(1).build(),
                        USER_TXN_ID.copyBuilder().nonce(2).build()))
                .isFalse();
    }

    @Test
    void childDetectionRequiresMatchingChildTxnIdAsChild() {
        assertThat(RecordCache.isChild(USER_TXN_ID, USER_TXN_ID)).isFalse();
        assertThat(RecordCache.isChild(
                        USER_TXN_ID,
                        USER_TXN_ID
                                .copyBuilder()
                                .accountID(AccountID.DEFAULT)
                                .nonce(1)
                                .build()))
                .isFalse();
    }

    @Test
    void emptyHistoryAsExpected() {
        final var subject = new RecordCache.History();
        assertThat(subject.userTransactionRecord()).isNull();
        assertThat(subject.priorityReceipt()).isSameAs(RecordCache.ReceiptSource.PENDING_RECEIPT);
        assertThat(subject.duplicateRecords()).isEmpty();
        assertThat(subject.duplicateCount()).isZero();
        assertThat(subject.orderedRecords()).isEmpty();
    }

    @Test
    void nonEmptyHistoryAsExpected() {
        final var userRecord = TransactionRecord.newBuilder()
                .receipt(TransactionReceipt.newBuilder().status(SUCCESS).build())
                .consensusTimestamp(new Timestamp(456, 0))
                .build();
        final var invalidRecord = TransactionRecord.newBuilder()
                .receipt(TransactionReceipt.newBuilder()
                        .status(INVALID_NODE_ACCOUNT)
                        .build())
                .consensusTimestamp(new Timestamp(123, 0))
                .build();
        final var childRecord = TransactionRecord.newBuilder()
                .receipt(TransactionReceipt.newBuilder().status(SUCCESS).build())
                .consensusTimestamp(new Timestamp(456, 1))
                .build();
        final var subject =
                new RecordCache.History(Set.of(0L), List.of(invalidRecord, userRecord), List.of(childRecord));
        assertThat(subject.userTransactionRecord()).isSameAs(userRecord);
        assertThat(subject.priorityReceipt()).isSameAs(userRecord.receiptOrThrow());
        assertThat(subject.duplicateRecords()).containsExactly(invalidRecord);
        assertThat(subject.duplicateCount()).isEqualTo(1);
        assertThat(subject.orderedRecords()).containsExactly(userRecord, childRecord, invalidRecord);
    }
}
