// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache;

import static com.hedera.node.app.spi.records.RecordCache.isChild;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.types.StreamMode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A {@link RecordSource} that uses a list of precomputed {@link TransactionRecord}s. Used in some tests and when
 * {@link BlockStreamConfig#streamMode()} is {@link StreamMode#BLOCKS} to  support queryable partial records after
 * reconnect or restart.
 */
public class PartialRecordSource implements RecordSource {
    @Nullable
    private final Long blockNumber;

    private final List<TransactionRecord> precomputedRecords;
    private final List<IdentifiedReceipt> identifiedReceipts;

    /**
     * Creates a new {@link PartialRecordSource} with the given block number and no precomputed records.
     *
     * @param blockNumber the block number to associate with the records in this source, or null if not applicable
     */
    public PartialRecordSource(@Nullable final Long blockNumber) {
        this.blockNumber = blockNumber;
        this.precomputedRecords = new ArrayList<>();
        this.identifiedReceipts = new ArrayList<>();
    }

    @VisibleForTesting
    public PartialRecordSource(@NonNull final TransactionRecord precomputedRecord, @Nullable final Long blockNumber) {
        this(List.of(precomputedRecord), blockNumber);
    }

    public PartialRecordSource(
            @NonNull final List<TransactionRecord> precomputedRecords, @Nullable final Long blockNumber) {
        requireNonNull(precomputedRecords);
        this.blockNumber = blockNumber;
        this.precomputedRecords = new ArrayList<>(precomputedRecords.size());
        identifiedReceipts = new ArrayList<>(precomputedRecords.size());
        precomputedRecords.forEach(this::incorporate);
    }

    public void incorporate(@NonNull final TransactionRecord precomputedRecord) {
        requireNonNull(precomputedRecord);

        var precomputedReceipt = precomputedRecord.receipt();
        if (blockNumber != null
                && precomputedReceipt != null
                && !Objects.equals(precomputedReceipt.blockNumber(), blockNumber)) {
            precomputedReceipt =
                    precomputedReceipt.copyBuilder().blockNumber(blockNumber).build();
        }
        final var blockNumberedRecord = precomputedReceipt == null
                ? precomputedRecord
                : precomputedRecord.copyBuilder().receipt(precomputedReceipt).build();
        precomputedRecords.add(blockNumberedRecord);
        identifiedReceipts.add(new IdentifiedReceipt(
                blockNumberedRecord.transactionIDOrThrow(), blockNumberedRecord.receiptOrThrow()));
    }

    @Override
    @VisibleForTesting
    public @Nullable Long blockNumber() {
        return blockNumber;
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
