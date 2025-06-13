// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import static com.hedera.hapi.platform.event.TransactionGroupRole.FIRST_CHILD;
import static com.hedera.hapi.platform.event.TransactionGroupRole.STANDALONE;
import static com.hedera.hapi.platform.event.TransactionGroupRole.STARTING_PARENT;
import static com.hedera.services.bdd.junit.support.validators.block.BlockContentsValidator.bodyFrom;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.platform.event.TransactionGroupRole;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionalUnit;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Splits a block into units for translation.
 */
public class BlockUnitSplit {
    private static final Set<TransactionGroupRole> NEW_UNIT_ROLES =
            EnumSet.of(STANDALONE, FIRST_CHILD, STARTING_PARENT);

    private final Deque<Bytes> pendingInnerTxns = new ArrayDeque<>();

    /**
     * Holds the parts of a transaction that are pending processing.
     */
    private static class PendingBlockTransactionParts {
        @Nullable
        private TransactionParts parts;

        @Nullable
        private TransactionResult result;

        @Nullable
        private List<TraceData> traces;

        @Nullable
        private List<TransactionOutput> outputs;

        @Nullable
        private TransactionGroupRole role;

        /**
         * Clears the pending parts.
         */
        void clear() {
            parts = null;
            result = null;
            outputs = null;
            traces = null;
            role = null;
        }

        /**
         * Indicates whether the pending parts are complete.
         *
         * @return whether the pending parts are complete
         */
        boolean areComplete() {
            return parts != null && result != null;
        }

        void addOutput(@NonNull final TransactionOutput output) {
            if (outputs == null) {
                outputs = new ArrayList<>();
            }
            outputs.add(output);
        }

        void addTrace(@NonNull final TraceData trace) {
            if (traces == null) {
                traces = new ArrayList<>();
            }
            traces.add(trace);
        }

        BlockTransactionParts toBlockTransactionParts() {
            requireNonNull(role);
            requireNonNull(parts);
            requireNonNull(result);
            return new BlockTransactionParts(parts, result, role, traces, outputs);
        }
    }

    @Nullable
    private List<StateChange> genesisStateChanges = new ArrayList<>();

    /**
     * Splits the given block into transactional units.
     *
     * @param block the block to split
     * @return the transactional units
     */
    public List<BlockTransactionalUnit> split(@NonNull final Block block) {
        final List<BlockTransactionalUnit> units = new ArrayList<>();

        PendingBlockTransactionParts pendingParts = new PendingBlockTransactionParts();
        final List<BlockTransactionParts> unitParts = new ArrayList<>();
        final List<StateChange> unitStateChanges = new ArrayList<>();
        int childIndex = 0;
        for (int i = 0, n = block.items().size(); i < n; i++) {
            final var item = block.items().get(i);
            switch (item.item().kind()) {
                case UNSET, RECORD_FILE ->
                    throw new IllegalStateException("Cannot split block with item of kind "
                            + item.item().kind());
                case BLOCK_HEADER, EVENT_HEADER, ROUND_HEADER, FILTERED_ITEM_HASH, BLOCK_PROOF -> {
                    // No-op
                }
                case EVENT_TRANSACTION -> {
                    final var eventTransaction = item.eventTransactionOrThrow();
                    if (eventTransaction.hasApplicationTransaction()) {
                        if (pendingParts.areComplete()) {
                            unitParts.add(pendingParts.toBlockTransactionParts());
                        }
                        final var nextParts = TransactionParts.from(eventTransaction.applicationTransactionOrThrow());
                        if (!unitParts.isEmpty() && NEW_UNIT_ROLES.contains(eventTransaction.transactionGroupRole())) {
                            completeAndAdd(units, unitParts, unitStateChanges);
                        }
                        pendingParts.clear();
                        pendingParts.role = eventTransaction.transactionGroupRole();
                        if (genesisStateChanges != null) {
                            unitStateChanges.addAll(genesisStateChanges);
                            genesisStateChanges = null;
                        }
                        pendingParts.parts = nextParts;
                        pendingInnerTxns.clear();

                        final var function = nextParts.function();
                        if (eventTransaction.transactionGroupRole() == STARTING_PARENT
                                && function == HederaFunctionality.ATOMIC_BATCH) {
                            try {
                                final var batchBody = bodyFrom(eventTransaction);
                                pendingInnerTxns.addAll(
                                        batchBody.atomicBatchOrThrow().transactions());
                            } catch (Exception e) {
                                throw new IllegalStateException("Failed to extract body from event transaction", e);
                            }
                        }
                    }
                }
                case TRANSACTION_RESULT -> {
                    if (pendingParts.parts != null) {
                        pendingParts.result = item.transactionResultOrThrow();
                        if (pendingParts.role == STARTING_PARENT) {
                            unitParts.add(pendingParts.toBlockTransactionParts());
                            pendingParts.clear();
                        }
                        break;
                    }
                    if (pendingInnerTxns.isEmpty()) {
                        throw new IllegalStateException("Result with no antecedent event-txn outside atomic-batch");
                    } else {
                        childIndex++;
                    }
                    if (!unitParts.isEmpty()) {
                        completeAndAdd(units, unitParts, unitStateChanges);
                    }

                    pendingParts.clear();
                    pendingParts.role = childIndex == 1
                            ? FIRST_CHILD
                            : childIndex < pendingInnerTxns.size() - 1
                                    ? TransactionGroupRole.MIDDLE_CHILD
                                    : TransactionGroupRole.LAST_CHILD;

                    // Pull the next inner txn from the batch list
                    final var txn = Transaction.newBuilder().signedTransactionBytes(requireNonNull(pendingInnerTxns.pollFirst())).build();
                    final var bytes = Transaction.PROTOBUF.toBytes(txn);
                    pendingParts.parts = TransactionParts.from(bytes);
                    pendingParts.result = item.transactionResultOrThrow();
                }
                case TRANSACTION_OUTPUT -> pendingParts.addOutput(item.transactionOutputOrThrow());
                case TRACE_DATA -> pendingParts.addTrace(item.traceDataOrThrow());
                case STATE_CHANGES -> {
                    if (genesisStateChanges != null) {
                        genesisStateChanges.addAll(item.stateChangesOrThrow().stateChanges());
                    } else {
                        unitStateChanges.addAll(item.stateChangesOrThrow().stateChanges());
                    }
                }
            }
        }
        if (pendingParts.areComplete()) {
            unitParts.add(pendingParts.toBlockTransactionParts());
            completeAndAdd(units, unitParts, unitStateChanges);
        }
        return units;
    }

    private void completeAndAdd(
            @NonNull final List<BlockTransactionalUnit> units,
            @NonNull final List<BlockTransactionParts> unitParts,
            @NonNull final List<StateChange> unitStateChanges) {
        units.add(new BlockTransactionalUnit(new ArrayList<>(unitParts), new LinkedList<>(unitStateChanges)));
        unitParts.clear();
        unitStateChanges.clear();
    }
}
