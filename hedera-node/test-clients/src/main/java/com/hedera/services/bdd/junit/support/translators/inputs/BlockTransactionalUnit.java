// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.inputs;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import edu.umd.cs.findbugs.annotations.NonNull;
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

    public BlockTransactionalUnit withBatchedTransactionParts() {
        boolean anyUnitMissing = false;
        for (final BlockTransactionParts parts : blockTransactionParts) {
            if (parts.transactionParts() == null) {
                anyUnitMissing = true;
                break;
            }
        }
        if (!anyUnitMissing) {
            return this;
        }
        // find atomic batch
        final var batchParts = blockTransactionParts.stream()
                .filter(parts -> parts.functionality() == HederaFunctionality.ATOMIC_BATCH)
                .findFirst()
                .orElseThrow();
        // get queue of inner txns
        final var innerTxns = batchParts.body().atomicBatchOrThrow().transactions().stream()
                .map(txn -> Transaction.PROTOBUF.toBytes(
                        Transaction.newBuilder().signedTransactionBytes(txn).build()))
                .map(TransactionParts::from)
                .toList();
        for (int i = 0; i < blockTransactionParts.size(); i++) {
            final var parts = blockTransactionParts.get(i);
            if (parts.transactionParts() == null) {
                // replace with inner transaction
                blockTransactionParts.set(i, parts.withParts(innerTxns.removeFirst()));
            }
        }
        return this;
    }
}
