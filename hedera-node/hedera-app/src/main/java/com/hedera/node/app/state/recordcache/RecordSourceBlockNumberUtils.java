// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.node.app.state.SingleTransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

final class RecordSourceBlockNumberUtils {
    private RecordSourceBlockNumberUtils() {}

    static @NonNull TransactionReceipt withBlockNumber(
            @NonNull final TransactionReceipt receipt, @Nullable final Long blockNumber) {
        requireNonNull(receipt);
        if (blockNumber == null || Objects.equals(receipt.blockNumber(), blockNumber)) {
            return receipt;
        }
        return receipt.copyBuilder().blockNumber(blockNumber).build();
    }

    static @NonNull TransactionRecord withBlockNumber(
            @NonNull final TransactionRecord record, @Nullable final Long blockNumber) {
        requireNonNull(record);
        final var receipt = record.receipt();
        if (receipt == null) {
            return record;
        }
        final var blockNumberedReceipt = withBlockNumber(receipt, blockNumber);
        if (blockNumberedReceipt == receipt) {
            return record;
        }
        return record.copyBuilder().receipt(blockNumberedReceipt).build();
    }

    static @NonNull RecordSource.IdentifiedReceipt withBlockNumber(
            @NonNull final RecordSource.IdentifiedReceipt identifiedReceipt, @Nullable final Long blockNumber) {
        requireNonNull(identifiedReceipt);
        final var blockNumberedReceipt = withBlockNumber(identifiedReceipt.receipt(), blockNumber);
        if (blockNumberedReceipt == identifiedReceipt.receipt()) {
            return identifiedReceipt;
        }
        return new RecordSource.IdentifiedReceipt(identifiedReceipt.txnId(), blockNumberedReceipt);
    }

    static @NonNull SingleTransactionRecord withBlockNumber(
            @NonNull final SingleTransactionRecord record, @Nullable final Long blockNumber) {
        requireNonNull(record);
        final var blockNumberedRecord = withBlockNumber(record.transactionRecord(), blockNumber);
        if (blockNumberedRecord == record.transactionRecord()) {
            return record;
        }
        return new SingleTransactionRecord(
                record.transaction(),
                blockNumberedRecord,
                record.transactionSidecarRecords(),
                record.transactionOutputs());
    }
}
