// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.spi.records.RecordSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link RecordSource} that overlays a block number onto all receipts and records from a delegate source.
 *
 * @param blockNumber the block number to apply
 * @param delegate the underlying source
 */
public record BlockNumberRecordSource(long blockNumber, @NonNull RecordSource delegate) implements RecordSource {
    public BlockNumberRecordSource {
        requireNonNull(delegate);
    }

    @Override
    public List<IdentifiedReceipt> identifiedReceipts() {
        return delegate.identifiedReceipts().stream()
                .map(identifiedReceipt ->
                        new IdentifiedReceipt(identifiedReceipt.txnId(), withBlockNumber(identifiedReceipt.receipt())))
                .toList();
    }

    @Override
    public void forEachTxnRecord(@NonNull final Consumer<TransactionRecord> action) {
        requireNonNull(action);
        delegate.forEachTxnRecord(record -> action.accept(withBlockNumber(record)));
    }

    @Override
    public TransactionReceipt receiptOf(@NonNull final TransactionID txnId) {
        requireNonNull(txnId);
        return withBlockNumber(delegate.receiptOf(txnId));
    }

    @Override
    public List<TransactionReceipt> childReceiptsOf(@NonNull final TransactionID txnId) {
        requireNonNull(txnId);
        return delegate.childReceiptsOf(txnId).stream().map(this::withBlockNumber).toList();
    }

    private TransactionReceipt withBlockNumber(@NonNull final TransactionReceipt receipt) {
        requireNonNull(receipt);
        if (blockNumber == 0 || receipt.blockNumber() == blockNumber) {
            return receipt;
        }
        return receipt.copyBuilder().blockNumber(blockNumber).build();
    }

    private TransactionRecord withBlockNumber(@NonNull final TransactionRecord record) {
        requireNonNull(record);
        final var receipt = record.receipt();
        if (receipt == null) {
            return record;
        }
        final var blockNumberedReceipt = withBlockNumber(receipt);
        if (blockNumberedReceipt == receipt) {
            return record;
        }
        return record.copyBuilder().receipt(blockNumberedReceipt).build();
    }
}
