// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache;

import static com.hedera.node.app.blocks.BlockItemsTranslator.BLOCK_ITEMS_TRANSLATOR;
import static com.hedera.node.app.spi.records.RecordCache.isChild;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.blocks.BlockItemsTranslator;
import com.hedera.node.app.blocks.impl.BlockStreamBuilder;
import com.hedera.node.app.blocks.impl.TranslationContext;
import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.node.app.state.SingleTransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link RecordSource} that lazily computes {@link TransactionRecord} and {@link TransactionReceipt} histories from
 * lists of {@link BlockItem}s and corresponding {@link TranslationContext}s.
 */
public class BlockRecordSource implements RecordSource {
    private final BlockItemsTranslator blockItemsTranslator;
    private final List<BlockStreamBuilder.Output> outputs;

    @Nullable
    private List<TransactionRecord> computedRecords;

    @Nullable
    private List<IdentifiedReceipt> computedReceipts;

    /**
     * Constructs a {@link BlockRecordSource} from a list of {@link BlockStreamBuilder.Output}s.
     * @param outputs the outputs
     */
    public BlockRecordSource(@NonNull final List<BlockStreamBuilder.Output> outputs) {
        this(BLOCK_ITEMS_TRANSLATOR, outputs);
    }

    /**
     * Constructs a {@link BlockRecordSource} from a list of {@link BlockStreamBuilder.Output}s, also
     * specifying the {@link BlockItemsTranslator} to use for creating receipts and records.
     * @param blockItemsTranslator the translator
     * @param outputs the outputs
     */
    @VisibleForTesting
    public BlockRecordSource(
            @NonNull final BlockItemsTranslator blockItemsTranslator,
            @NonNull final List<BlockStreamBuilder.Output> outputs) {
        this.blockItemsTranslator = requireNonNull(blockItemsTranslator);
        this.outputs = requireNonNull(outputs);
    }

    /**
     * Returns a list of {@link SingleTransactionRecord}s translated from the block outputs,
     * each containing the original transaction, translated record, and empty sidecars/outputs.
     */
    public @NonNull List<SingleTransactionRecord> precomputedRecords() {
        final var records = new ArrayList<SingleTransactionRecord>();
        for (final var output : outputs) {
            final var context = output.translationContext();
            final var txnRecord = output.toRecord(blockItemsTranslator);
            final var serialized = context.serializedSignedTx();
            final var transaction = Transaction.newBuilder()
                    .signedTransactionBytes(
                            serialized != null ? serialized : SignedTransaction.PROTOBUF.toBytes(context.signedTx()))
                    .build();
            records.add(new SingleTransactionRecord(
                    transaction, txnRecord, List.of(), new SingleTransactionRecord.TransactionOutputs(null)));
        }
        return records;
    }

    /**
     * For each {@link BlockItem} in the source, apply the given action.
     *
     * @param action the action to apply
     */
    public void forEachItem(@NonNull final Consumer<BlockItem> action) {
        requireNonNull(action);
        outputs.forEach(output -> output.forEachItem(action));
    }

    @Override
    public List<IdentifiedReceipt> identifiedReceipts() {
        return computedReceipts();
    }

    @Override
    public void forEachTxnRecord(@NonNull final Consumer<TransactionRecord> action) {
        requireNonNull(action);
        computedRecords().forEach(action);
    }

    @Override
    public TransactionReceipt receiptOf(@NonNull final TransactionID txnId) {
        requireNonNull(txnId);
        for (final var receipt : computedReceipts()) {
            if (txnId.equals(receipt.txnId())) {
                return receipt.receipt();
            }
        }
        throw new IllegalArgumentException();
    }

    @Override
    public List<TransactionReceipt> childReceiptsOf(@NonNull final TransactionID txnId) {
        requireNonNull(txnId);
        final List<TransactionReceipt> receipts = new ArrayList<>();
        for (final var receipt : computedReceipts()) {
            if (isChild(txnId, receipt.txnId())) {
                receipts.add(receipt.receipt());
            }
        }
        return receipts;
    }

    private List<IdentifiedReceipt> computedReceipts() {
        if (computedReceipts == null) {
            // Mutate the list of outputs before making it visible to another traversing thread
            final List<IdentifiedReceipt> computation = new ArrayList<>();
            for (final var output : outputs) {
                computation.add(output.toIdentifiedReceipt(blockItemsTranslator));
            }
            computedReceipts = computation;
        }
        return computedReceipts;
    }

    private List<TransactionRecord> computedRecords() {
        if (computedRecords == null) {
            // Mutate the list of outputs before making it visible to another traversing thread
            final List<TransactionRecord> computation = new ArrayList<>();
            for (final var output : outputs) {
                computation.add(output.toRecord(blockItemsTranslator));
            }
            computedRecords = computation;
        }
        return computedRecords;
    }
}
