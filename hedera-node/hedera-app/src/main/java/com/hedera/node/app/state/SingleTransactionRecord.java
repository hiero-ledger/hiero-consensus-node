// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * A record of a single transaction, including the transaction, record and the sidecar records.
 */
public record SingleTransactionRecord(
        @NonNull Transaction transaction,
        @NonNull TransactionRecord transactionRecord,
        @NonNull List<TransactionSidecarRecord> transactionSidecarRecords,
        @NonNull TransactionOutputs transactionOutputs) {
    public SingleTransactionRecord {
        requireNonNull(transaction, "transaction must not be null");
        requireNonNull(transactionRecord, "record must not be null");
        requireNonNull(transactionSidecarRecords, "transactionSidecarRecords must not be null");
        requireNonNull(transactionOutputs, "transactionOutputs must not be null");
    }

    // This is used by BlockStream, and is not serialized.
    public record TransactionOutputs(@Nullable TokenType tokenType) {}
}
