// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache;

import static com.hedera.node.app.spi.records.RecordCache.isChild;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.types.StreamMode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link RecordSource} that uses a list of precomputed {@link TransactionRecord}s. Used in some tests and when
 * {@link BlockStreamConfig#streamMode()} is {@link StreamMode#BLOCKS} to  support queryable partial records after
 * reconnect or restart.
 */
public class PartialRecordSource implements RecordSource {
    private final List<TransactionRecord> precomputedRecords;
    private final List<IdentifiedReceipt> identifiedReceipts;

    public PartialRecordSource() {
        this.precomputedRecords = new ArrayList<>();
        this.identifiedReceipts = new ArrayList<>();
    }

    public PartialRecordSource(@NonNull final TransactionRecord precomputedRecord) {
        this(List.of(precomputedRecord));
    }

    public PartialRecordSource(@NonNull final List<TransactionRecord> precomputedRecords) {
        requireNonNull(precomputedRecords);
        this.precomputedRecords = requireNonNull(precomputedRecords);
        identifiedReceipts = new ArrayList<>();
        for (final var precomputed : precomputedRecords) {
            identifiedReceipts.add(
                    new IdentifiedReceipt(precomputed.transactionIDOrThrow(), precomputed.receiptOrThrow()));
        }
    }

    public void incorporate(@NonNull final TransactionRecord precomputedRecord) {
        requireNonNull(precomputedRecord);
        precomputedRecords.add(precomputedRecord);
    }

    @Override
    public List<IdentifiedReceipt> identifiedReceipts() {
        return identifiedReceipts;
    }

    @Override
    public void forEachTxnRecord(@NonNull final Consumer<TransactionRecord> action) {
        requireNonNull(action);
        precomputedRecords.forEach(action);
    }

    @Override
    public TransactionReceipt receiptOf(@NonNull final TransactionID txnId) {
        requireNonNull(txnId);
        for (final var precomputed : precomputedRecords) {
            if (txnId.equals(precomputed.transactionIDOrThrow())) {
                return precomputed.receiptOrThrow();
            }
        }
        throw new IllegalArgumentException();
    }

    @Override
    public List<TransactionReceipt> childReceiptsOf(@NonNull TransactionID txnId) {
        requireNonNull(txnId);
        return precomputedRecords.stream()
                .filter(r -> isChild(txnId, r.transactionIDOrThrow()))
                .map(TransactionRecord::receiptOrThrow)
                .toList();
    }
}
