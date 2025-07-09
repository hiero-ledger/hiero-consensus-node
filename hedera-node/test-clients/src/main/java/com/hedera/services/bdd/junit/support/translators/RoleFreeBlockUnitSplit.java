// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_SIGN;
import static com.hedera.node.app.spi.records.RecordCache.matchesExceptNonce;
import static com.hedera.node.app.workflows.handle.throttle.DispatchUsageManager.CONTRACT_OPERATIONS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionalUnit;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Assertions;

/**
 * Splits a block into transactional units (that is, a set of Merkle state changes
 * plus one or more logical {@link BlockTransactionParts}) without relying on
 * per-transaction roles.
 * <p>
 * Works by accumulating an entire block's worth of items and then identifying
 * top-level transactions by the presence of a following {@link StateChanges}.
 * For a given top-level transaction {@code N}, any synthetic transactions
 * between it and top-level transaction {@code N+1} must have a (possibly empty)
 * prefix belonging to transactional unit {@code N} and a (possibly empty) suffix
 * belonging to transactional unit {@code N+1}.
 * <p>
 * The prefix of synthetics belonging to unit {@code N} can be very diverse due to
 * scheduled and atomic batch transactions. However, the suffix of synthetics
 * belonging to unit {@code N+1} are easily identified as their
 * {@link TransactionID}s will always share account id and valid start with the
 * {@code N+1} top-level transaction, achieving uniqueness via nonce only.
 */
public class RoleFreeBlockUnitSplit {
    private static final Set<HederaFunctionality> TRIGGERING_OPS = EnumSet.of(SCHEDULE_CREATE, SCHEDULE_SIGN);

    /**
     * Indexes of transactions in the block.
     */
    private final NavigableSet<Integer> txIndexes = new TreeSet<>();
    /**
     * Indexes of results in the block.
     */
    private final NavigableSet<Integer> resultIndexes = new TreeSet<>();
    /**
     * Indexes of state changes in the block.
     */
    private final NavigableSet<Integer> stateChangeIndexes = new TreeSet<>();
    /**
     * Map from index in the block to top-level transaction ID.
     */
    private final NavigableMap<Integer, TransactionID> topLevelIds = new TreeMap<>();
    /**
     * Map from index to unit assignment.
     */
    private final Map<Integer, Integer> unitAssignments = new HashMap<>();

    /**
     * Splits a block into transactional units.
     * @param block the block to split
     * @return the transactional units, each with its logical tx parts that explain that unit's state changes
     */
    public List<BlockTransactionalUnit> split(@NonNull final Block block) {
        return split(block.items());
    }

    /**
     * Splits a list of block items into transactional units.
     * @param items the list of block items to split
     * @return the transactional units, each with its logical tx parts that explain that unit's state changes
     */
    public List<BlockTransactionalUnit> split(@NonNull final List<BlockItem> items) {
        clear();

        final int n = items.size();
        final Map<Integer, TransactionParts> parsedParts = new HashMap<>();
        final IntFunction<TransactionParts> getParts = i -> parsedParts.computeIfAbsent(i, k -> {
            final var item = items.get(i);
            if (item.hasTransactionResult()) {
                return null;
            } else {
                return TransactionParts.from(item.eventTransactionOrThrow().applicationTransactionOrThrow());
            }
        });

        // First scan to get the indexes of results, state changes and transactions
        for (int i = 0; i < n; i++) {
            final var item = items.get(i);
            if (item.hasStateChanges()) {
                if (hasKvOrEmptyChanges(item.stateChangesOrThrow())) {
                    stateChangeIndexes.add(i);
                }
            } else if (item.hasEventTransaction()) {
                txIndexes.add(i);
            } else if (item.hasTransactionResult()) {
                resultIndexes.add(i);
            }
        }
        if (txIndexes.isEmpty()) {
            return List.of();
        }
        // Insert logical tx indexes for atomic batch inners
        final Set<Integer> batchInnerIdxs = new HashSet<>();
        resultIndexes.forEach(i -> {
            final var prevTxIndex = requireNonNull(txIndexes.lower(i));
            final var prevResultIndex = resultIndexes.lower(i);
            if (prevResultIndex != null && prevResultIndex > prevTxIndex) {
                batchInnerIdxs.add(i);
            }
        });
        txIndexes.addAll(batchInnerIdxs);
        // Now we can easily identify top-level transactions because they are
        // exactly the transactions that have no intervening transaction between
        // themselves and the next state change
        final List<List<BlockItem>> partItems = new ArrayList<>(txIndexes.size());
        txIndexes.forEach(i -> {
            // Check if a non-inner transaction is top-level (i.e., is the first
            // transaction preceding the next state changes item in the stream,
            // or the only non-inner transaction in a block)
            if (!batchInnerIdxs.contains(i)) {
                final var nextStateChangeIndex = stateChangeIndexes.higher(i);
                if (nextStateChangeIndex != null) {
                    final int j = requireNonNull(txIndexes.lower(nextStateChangeIndex));
                    if (i == j) {
                        final var txId = getParts.apply(i).transactionIdOrThrow();
                        topLevelIds.put(i, txId);
                    }
                }
            }
            final var nextTxIndex = txIndexes.higher(i);
            final int j = nextTxIndex == null ? n : nextTxIndex;
            final List<BlockItem> txItems = new ArrayList<>();
            boolean done = false;
            for (int k = i; k < j && !done; k++) {
                final var item = items.get(k);
                switch (item.item().kind()) {
                    case EVENT_TRANSACTION, TRANSACTION_RESULT, TRANSACTION_OUTPUT, TRACE_DATA, STATE_CHANGES ->
                        txItems.add(item);
                    default -> done = true;
                }
            }
            partItems.add(txItems);
        });
        // And now we can assign unit ids to each tx's index
        final AtomicInteger unitIdx = new AtomicInteger(0);
        txIndexes.forEach(i -> {
            if (topLevelIds.containsKey(i)) {
                // Every top-level transaction is a unit
                unitAssignments.put(i, unitIdx.getAndIncrement());
            } else {
                final var maybeParts = getParts.apply(i);
                if (maybeParts == null) {
                    // Batch inner, always part of the current unit
                    unitAssignments.put(i, unitIdx.get() - 1);
                } else {
                    final var prevTopLevelIdx = topLevelIds.lowerKey(i);
                    final var nextTopLevelIdx = topLevelIds.higherKey(i);
                    if (prevTopLevelIdx != null && nextTopLevelIdx != null) {
                        final var txId = maybeParts.transactionIdOrThrow();
                        final var nextTxId = getParts.apply(nextTopLevelIdx).transactionIdOrThrow();
                        if (matchesExceptNonce(txId, nextTxId)) {
                            // Sandwiched between top-level and matching txId with latter
                            unitAssignments.put(i, unitIdx.get());
                        } else {
                            // Sandwiched between top-level and not matching txId of latter
                            unitAssignments.put(i, unitIdx.get() - 1);
                        }
                    } else if (nextTopLevelIdx == null) {
                        // No following top-level transaction to even consider
                        unitAssignments.put(i, unitIdx.get() - 1);
                    } else {
                        // Preceding child of first top-level transaction
                        unitAssignments.put(i, unitIdx.get());
                    }
                }
            }
        });

        final List<BlockTransactionalUnit> units = new ArrayList<>();
        // Parallel iterators for the tx indexes and their items
        final Iterator<Integer> idxIter = txIndexes.iterator();
        final Iterator<List<BlockItem>> itemsIter = partItems.iterator();
        // Context for the current unit being built
        int currentUnit = -1;
        List<StateChange> stateChanges = null;
        List<BlockTransactionParts> unitParts = null;
        for (int i = 0, m = partItems.size(); i < m; i++) {
            final int idx = idxIter.next();
            final int unitHere = unitAssignments.get(idx);
            if (currentUnit != unitHere) {
                if (stateChanges != null) {
                    units.add(new BlockTransactionalUnit(unitParts, stateChanges));
                }
                stateChanges = new ArrayList<>();
                unitParts = new ArrayList<>();
                currentUnit = unitHere;
            }
            final var nextPartItems = itemsIter.next();
            final var pending = new PendingBlockTransactionParts();
            for (final var item : nextPartItems) {
                switch (item.item().kind()) {
                    case EVENT_TRANSACTION -> pending.addPartsEnforcingOrder(getParts.apply(idx));
                    case TRANSACTION_RESULT -> pending.addResultEnforcingOrder(item.transactionResultOrThrow());
                    case TRANSACTION_OUTPUT -> pending.addOutputEnforcingOrder(item.transactionOutputOrThrow());
                    case TRACE_DATA -> pending.addTraceEnforcingOrder(item.traceDataOrThrow());
                    case STATE_CHANGES ->
                        requireNonNull(stateChanges)
                                .addAll(item.stateChangesOrThrow().stateChanges());
                    default -> {
                        // No-op
                    }
                }
            }
            requireNonNull(unitParts);
            final boolean isTopLevel = topLevelIds.containsKey(idx);
            final boolean usesEnrichedLegacyRecord =
                    isTopLevel || nextContractOpUsesEnrichedLegacyRecord(unitParts, pending);
            unitParts.add(pending.toBlockTransactionParts(topLevelIds.containsKey(idx), usesEnrichedLegacyRecord));
        }
        if (unitParts != null) {
            units.add(new BlockTransactionalUnit(unitParts, stateChanges));
        }
        return units;
    }

    /**
     * Checks if this is the first contract operation in a unit whose parent was top-level schedule operation.
     * @param unitSoFar the unit so far, which is a list of {@link BlockTransactionParts}
     * @param pendingParts the pending parts of the next transaction
     * @return true if the next contract operation uses enriched legacy record, false otherwise
     */
    private boolean nextContractOpUsesEnrichedLegacyRecord(
            @NonNull final List<BlockTransactionParts> unitSoFar,
            @NonNull final PendingBlockTransactionParts pendingParts) {
        if (unitSoFar.isEmpty()) {
            return false;
        } else {
            BlockTransactionParts topLevelParts = null;
            for (final var parts : unitSoFar) {
                if (parts.isTopLevel()) {
                    topLevelParts = parts;
                    break;
                }
            }
            return topLevelParts != null
                    && TRIGGERING_OPS.contains(topLevelParts.functionality())
                    && CONTRACT_OPERATIONS.contains(
                            requireNonNull(pendingParts.parts).function())
                    && unitSoFar.stream().noneMatch(parts -> CONTRACT_OPERATIONS.contains(parts.functionality()));
        }
    }

    private void clear() {
        stateChangeIndexes.clear();
        unitAssignments.clear();
        resultIndexes.clear();
        topLevelIds.clear();
        txIndexes.clear();
    }

    private static boolean hasKvOrEmptyChanges(@NonNull final StateChanges stateChanges) {
        final var changes = stateChanges.stateChanges();
        return changes.isEmpty() || changes.stream().anyMatch(change -> change.hasMapUpdate() || change.hasMapDelete());
    }

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

        void addPartsEnforcingOrder(@NonNull final TransactionParts parts) {
            if (this.parts != null) {
                Assertions.fail("Logical tx already has parts " + this.parts + ", cannot update to parts " + parts);
            }
            if (result != null) {
                Assertions.fail("Logical tx already has a result " + result + ", cannot add parts " + parts);
            }
            if (traces != null) {
                Assertions.fail("Logical tx already has traces " + traces + ", cannot add parts " + parts);
            }
            if (outputs != null) {
                Assertions.fail("Logical tx already has outputs " + outputs + ", cannot add parts " + parts);
            }
            this.parts = parts;
        }

        void addResultEnforcingOrder(@NonNull final TransactionResult result) {
            if (this.result != null) {
                Assertions.fail("Logical tx already has a result " + this.result + ", cannot add result " + result);
            }
            if (traces != null) {
                Assertions.fail("Logical tx already has traces " + traces + ", cannot add result " + result);
            }
            if (outputs != null) {
                Assertions.fail("Logical tx already has outputs " + outputs + ", cannot add result " + result);
            }
            this.result = result;
        }

        void addOutputEnforcingOrder(@NonNull final TransactionOutput output) {
            if (result == null) {
                Assertions.fail("Logical tx has no result, cannot add output " + output);
            }
            if (outputs == null) {
                outputs = new ArrayList<>();
            }
            outputs.add(output);
        }

        void addTraceEnforcingOrder(@NonNull final TraceData trace) {
            if (result == null) {
                Assertions.fail("Logical tx has no result, cannot add trace " + trace);
            }
            if (traces == null) {
                traces = new ArrayList<>();
            }
            traces.add(trace);
        }

        BlockTransactionParts toBlockTransactionParts(final boolean isTopLevel, final boolean hasEnrichedLegacyRecord) {
            // The only absolute requirement is the result is not null
            requireNonNull(result);
            return new BlockTransactionParts(parts, result, traces, outputs, isTopLevel, hasEnrichedLegacyRecord);
        }
    }
}
