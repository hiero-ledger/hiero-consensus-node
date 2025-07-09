// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.inputs;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayDeque;
import java.util.List;

/**
 * A grouping of block stream information used as input to record translation, where all the information is
 * linked to the same {@link TransactionID} and hence is part of the same transactional unit.
 * <p>
 * May include multiple logical HAPI transactions, and the state changes they produce.
 */
public record BlockTransactionalUnit(
        @NonNull List<BlockTransactionParts> blockTransactionParts, @NonNull List<StateChange> stateChanges) {
    /**
     * Returns all trace data in this unit.
     */
    public List<TraceData> allTraces() {
        return blockTransactionParts.stream()
                .filter(BlockTransactionParts::hasTraces)
                .flatMap(parts -> parts.tracesOrThrow().stream())
                .toList();
    }

    /**
     * Returns the unit with the inner transactions of any atomic batch transaction parts replaced with their
     * respective inner transactions.
     * @return the unit with inner transactions replaced with their respective inner transactions
     */
    public BlockTransactionalUnit withBatchTransactionParts() {
        boolean anyUnitMissing = false;
        for (final var parts : blockTransactionParts) {
            if (parts.transactionParts() == null) {
                anyUnitMissing = true;
                break;
            }
        }
        // If no unit is missing, then we can return the original unit. This means there are no batch transactions
        if (!anyUnitMissing) {
            return this;
        }
        // find atomic batch transaction parts
        final var batchParts = blockTransactionParts.stream()
                .filter(parts -> parts.functionality() == HederaFunctionality.ATOMIC_BATCH)
                .findFirst()
                .orElseThrow();
        // get queue of inner transactions from the atomic batch parts
        final var innerTxns = new ArrayDeque<>(batchParts.body().atomicBatchOrThrow().transactions().stream()
                .map(txn -> Transaction.PROTOBUF.toBytes(
                        Transaction.newBuilder().signedTransactionBytes(txn).build()))
                .map(TransactionParts::from)
                .toList());
        // Insert the inner transactions into the block transaction parts. Once we insert them, we can
        //  do the rest of the logic like usual
        for (int i = 0; i < blockTransactionParts.size(); i++) {
            final var parts = blockTransactionParts.get(i);
            if (parts.transactionParts() == null) {
                // replace it with inner transaction
                blockTransactionParts.set(i, parts.withPartsFromBatchParent(innerTxns.removeFirst()));
            }
        }
        return this;
    }
}
