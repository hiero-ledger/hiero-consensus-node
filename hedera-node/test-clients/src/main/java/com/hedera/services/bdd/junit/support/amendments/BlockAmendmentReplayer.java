// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.amendments;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.network.BlockAmendment;
import com.hedera.hapi.node.network.BlockAmendmentOperation;
import com.hedera.hapi.node.network.BlockAmendmentTransactionBody;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reference implementation of the block amendment replay algorithm.
 *
 * <p>Every position in an amendment refers to the <i>original</i> items of the amended block (header through
 * footer), never to items inserted by an amendment. The amended view of a block is therefore produced by a
 * single merge-walk over the original items, with no index shifting:
 * <ul>
 *     <li>Inserts at the same gap are all kept, in amendment consensus order and then operation order.</li>
 *     <li>For replaces and removes of the same element, the latest one wins.</li>
 *     <li>An item-level replace or remove takes precedence over state change edits to that item.</li>
 *     <li>An amendment that voids previous amendments discards every earlier amendment of the block, so its
 *     operations apply to the original block; later amendments compose on top of it as usual.</li>
 *     <li>An amendment with any out-of-range or ill-typed operation is ignored in its entirety, including any
 *     request to void previous amendments.</li>
 * </ul>
 *
 * <p>One {@code BlockAmendment} transaction may amend several blocks, with at most one amendment per block. Use
 * {@link #amendmentsOf(long, List)} to select the amendments of one block from a sequence of transactions.
 *
 * <p>Because a {@code VirtualMap}'s root hash depends on the order in which keys are added and removed, state
 * must be replayed by applying the {@link #stateChangesInReplayOrder(List)} individually and in order.
 */
public final class BlockAmendmentReplayer {
    private BlockAmendmentReplayer() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** An item-level or state-change-level replace (with a replacement) or remove (without one). */
    private record Disposition(Object replacement) {
        boolean isRemove() {
            return replacement == null;
        }
    }

    /** The parsed operations of one valid amendment. */
    private static final class ParsedOperations {
        private final Map<Integer, List<BlockItem>> itemInserts = new HashMap<>();
        private final Map<Integer, Disposition> itemDispositions = new HashMap<>();
        private final Map<Integer, Map<Integer, List<StateChange>>> stateChangeInserts = new HashMap<>();
        private final Map<Integer, Map<Integer, Disposition>> stateChangeDispositions = new HashMap<>();
    }

    /**
     * Returns the amendments of one block from a sequence of successful {@code BlockAmendment} transactions,
     * skipping transactions that do not amend it.
     *
     * @param blockNumber the number of the amended block
     * @param transactions the transactions, in consensus order
     * @return the amendments of the block, in consensus order
     */
    public static List<BlockAmendment> amendmentsOf(
            final long blockNumber, @NonNull final List<BlockAmendmentTransactionBody> transactions) {
        requireNonNull(transactions);
        return transactions.stream()
                .flatMap(transaction -> transaction.amendments().stream())
                .filter(amendment -> amendment.blockNumber() == blockNumber)
                .toList();
    }

    /**
     * Returns the amended view of a block: its original items (header through footer) with its amendments applied,
     * in order, starting from the latest valid amendment that voids previous amendments.
     *
     * @param blockItems the items of the block as stored; anything after the footer is ignored
     * @param amendments the amendments of this block, in consensus order
     * @return the amended items, header through footer
     * @throws IllegalArgumentException if the block has no header or footer, or if any amendment is of another block
     */
    public static List<BlockItem> amendedItems(
            @NonNull final List<BlockItem> blockItems, @NonNull final List<BlockAmendment> amendments) {
        requireNonNull(blockItems);
        requireNonNull(amendments);
        final var original = originalItems(blockItems);
        final int footerIndex = original.size() - 1;
        final long blockNumber = original.getFirst().blockHeaderOrThrow().number();
        for (final var amendment : amendments) {
            if (amendment.blockNumber() != blockNumber) {
                throw new IllegalArgumentException(
                        "Amendment of block " + amendment.blockNumber() + " applied to block " + blockNumber);
            }
        }

        // Merge every valid amendment's operations, in consensus order then operation order; an amendment that
        // voids previous amendments starts over from the original block
        var merged = new ParsedOperations();
        for (final var amendment : amendments) {
            final var parsed = parse(amendment, original, footerIndex);
            if (parsed.isEmpty()) {
                continue;
            }
            if (amendment.voidPreviousAmendments()) {
                merged = new ParsedOperations();
            }
            mergeInto(merged, parsed.get());
        }

        final List<BlockItem> amended = new ArrayList<>();
        for (int i = 0; i <= footerIndex; i++) {
            final var disposition = merged.itemDispositions.get(i);
            if (disposition != null) {
                if (!disposition.isRemove()) {
                    amended.add((BlockItem) disposition.replacement());
                }
            } else if (original.get(i).hasStateChanges()
                    && (merged.stateChangeInserts.containsKey(i) || merged.stateChangeDispositions.containsKey(i))) {
                amended.add(amendedStateChangesItem(original.get(i), i, merged));
            } else {
                amended.add(original.get(i));
            }
            if (i < footerIndex) {
                amended.addAll(merged.itemInserts.getOrDefault(i, List.of()));
            }
        }
        return amended;
    }

    /**
     * Returns every state change in the given items, flattened in the order a replayer must apply them.
     *
     * @param items block items
     * @return the state changes in replay order
     */
    public static List<StateChange> stateChangesInReplayOrder(@NonNull final List<BlockItem> items) {
        return items.stream()
                .filter(BlockItem::hasStateChanges)
                .flatMap(item -> item.stateChangesOrThrow().stateChanges().stream())
                .toList();
    }

    /**
     * Returns the original items of a block: everything from the header through the footer.
     *
     * @param blockItems the items of the block as stored
     * @return the original items
     * @throws IllegalArgumentException if the block does not start with a header or has no footer
     */
    public static List<BlockItem> originalItems(@NonNull final List<BlockItem> blockItems) {
        if (blockItems.isEmpty() || !blockItems.getFirst().hasBlockHeader()) {
            throw new IllegalArgumentException("Block does not start with a header");
        }
        for (int i = 0; i < blockItems.size(); i++) {
            if (blockItems.get(i).hasBlockFooter()) {
                return List.copyOf(blockItems.subList(0, i + 1));
            }
        }
        throw new IllegalArgumentException("Block has no footer");
    }

    private static BlockItem amendedStateChangesItem(
            @NonNull final BlockItem item, final int itemIndex, @NonNull final ParsedOperations merged) {
        final var stateChanges = item.stateChangesOrThrow();
        final var originalChanges = stateChanges.stateChanges();
        final var inserts = merged.stateChangeInserts.getOrDefault(itemIndex, Map.of());
        final var dispositions = merged.stateChangeDispositions.getOrDefault(itemIndex, Map.of());
        final List<StateChange> changes = new ArrayList<>();
        for (int p = 0; p <= originalChanges.size(); p++) {
            changes.addAll(inserts.getOrDefault(p, List.of()));
            if (p < originalChanges.size()) {
                final var disposition = dispositions.get(p);
                if (disposition == null) {
                    changes.add(originalChanges.get(p));
                } else if (!disposition.isRemove()) {
                    changes.add((StateChange) disposition.replacement());
                }
            }
        }
        return BlockItem.newBuilder()
                .stateChanges(new StateChanges(stateChanges.consensusTimestamp(), changes))
                .build();
    }

    private static void mergeInto(@NonNull final ParsedOperations merged, @NonNull final ParsedOperations parsed) {
        parsed.itemInserts.forEach((i, items) ->
                merged.itemInserts.computeIfAbsent(i, k -> new ArrayList<>()).addAll(items));
        merged.itemDispositions.putAll(parsed.itemDispositions);
        parsed.stateChangeInserts.forEach((i, byGap) -> byGap.forEach((p, changes) -> merged.stateChangeInserts
                .computeIfAbsent(i, k -> new HashMap<>())
                .computeIfAbsent(p, k -> new ArrayList<>())
                .addAll(changes)));
        parsed.stateChangeDispositions.forEach((i, byIndex) -> merged.stateChangeDispositions
                .computeIfAbsent(i, k -> new HashMap<>())
                .putAll(byIndex));
    }

    /**
     * Parses and bounds-checks one amendment against the original block, returning empty if any operation is
     * invalid (in which case the whole amendment is ignored).
     */
    private static Optional<ParsedOperations> parse(
            @NonNull final BlockAmendment amendment, @NonNull final List<BlockItem> original, final int footerIndex) {
        final var parsed = new ParsedOperations();
        try {
            for (final var operation : amendment.operations()) {
                if (!parseOperation(operation, original, footerIndex, parsed)) {
                    return Optional.empty();
                }
            }
        } catch (ParseException e) {
            return Optional.empty();
        }
        return Optional.of(parsed);
    }

    private static boolean parseOperation(
            @NonNull final BlockAmendmentOperation operation,
            @NonNull final List<BlockItem> original,
            final int footerIndex,
            @NonNull final ParsedOperations parsed)
            throws ParseException {
        return switch (operation.operation().kind()) {
            case UNSET -> false;
            case INSERT_BLOCK_ITEMS -> {
                final var insert = operation.insertBlockItemsOrThrow();
                final int after = insert.afterItemIndex();
                if (after < 0 || after >= footerIndex) {
                    yield false;
                }
                parsed.itemInserts.put(after, parseItems(insert.blockItems()));
                yield true;
            }
            case REPLACE_BLOCK_ITEM -> {
                final var replace = operation.replaceBlockItemOrThrow();
                if (!isAmendableItemIndex(replace.itemIndex(), footerIndex)) {
                    yield false;
                }
                parsed.itemDispositions.put(
                        replace.itemIndex(), new Disposition(BlockItem.PROTOBUF.parse(replace.blockItem())));
                yield true;
            }
            case REMOVE_BLOCK_ITEM -> {
                final int index = operation.removeBlockItemOrThrow().itemIndex();
                if (!isAmendableItemIndex(index, footerIndex)) {
                    yield false;
                }
                parsed.itemDispositions.put(index, new Disposition(null));
                yield true;
            }
            case INSERT_STATE_CHANGES -> {
                final var insert = operation.insertStateChangesOrThrow();
                final var changes = originalStateChanges(original, insert.itemIndex(), footerIndex);
                final int before = insert.beforeStateChangeIndex();
                if (changes == null || before < 0 || before > changes.size()) {
                    yield false;
                }
                parsed.stateChangeInserts
                        .computeIfAbsent(insert.itemIndex(), k -> new HashMap<>())
                        .put(before, parseStateChanges(insert.stateChanges()));
                yield true;
            }
            case REPLACE_STATE_CHANGE -> {
                final var replace = operation.replaceStateChangeOrThrow();
                final var changes = originalStateChanges(original, replace.itemIndex(), footerIndex);
                final int index = replace.stateChangeIndex();
                if (changes == null || index < 0 || index >= changes.size()) {
                    yield false;
                }
                parsed.stateChangeDispositions
                        .computeIfAbsent(replace.itemIndex(), k -> new HashMap<>())
                        .put(index, new Disposition(StateChange.PROTOBUF.parse(replace.stateChange())));
                yield true;
            }
            case REMOVE_STATE_CHANGE -> {
                final var remove = operation.removeStateChangeOrThrow();
                final var changes = originalStateChanges(original, remove.itemIndex(), footerIndex);
                final int index = remove.stateChangeIndex();
                if (changes == null || index < 0 || index >= changes.size()) {
                    yield false;
                }
                parsed.stateChangeDispositions
                        .computeIfAbsent(remove.itemIndex(), k -> new HashMap<>())
                        .put(index, new Disposition(null));
                yield true;
            }
        };
    }

    /** The header (index 0) and footer may never be replaced or removed. */
    private static boolean isAmendableItemIndex(final int index, final int footerIndex) {
        return index > 0 && index < footerIndex;
    }

    private static List<StateChange> originalStateChanges(
            @NonNull final List<BlockItem> original, final int itemIndex, final int footerIndex) {
        if (itemIndex <= 0
                || itemIndex >= footerIndex
                || !original.get(itemIndex).hasStateChanges()) {
            return null;
        }
        return original.get(itemIndex).stateChangesOrThrow().stateChanges();
    }

    private static List<BlockItem> parseItems(@NonNull final List<Bytes> serialized) throws ParseException {
        final List<BlockItem> items = new ArrayList<>(serialized.size());
        for (final var bytes : serialized) {
            items.add(BlockItem.PROTOBUF.parse(bytes));
        }
        return items;
    }

    private static List<StateChange> parseStateChanges(@NonNull final List<Bytes> serialized) throws ParseException {
        final List<StateChange> changes = new ArrayList<>(serialized.size());
        for (final var bytes : serialized) {
            changes.add(StateChange.PROTOBUF.parse(bytes));
        }
        return changes;
    }
}
