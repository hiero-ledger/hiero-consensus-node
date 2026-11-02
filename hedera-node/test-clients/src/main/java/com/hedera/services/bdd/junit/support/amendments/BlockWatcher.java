// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.amendments;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.services.bdd.junit.support.BlockSourceFactory;
import com.hedera.services.bdd.junit.support.StreamDataListener;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Collects the blocks a network produces after this watcher starts, from whichever source the spec's network
 * uses (block files on disk, or a block node), so a test can locate items in specific blocks.
 */
public final class BlockWatcher implements StreamDataListener, AutoCloseable {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private final ConcurrentSkipListMap<Long, Block> blocks = new ConcurrentSkipListMap<>();
    private Runnable unsubscribe = () -> {};

    /**
     * The location of a state change in a block.
     *
     * @param block the block
     * @param itemIndex the index of the {@code StateChanges} item within the block
     * @param stateChangeIndex the index of the state change within that item
     * @param stateChange the state change
     */
    public record StateChangeLocation(
            @NonNull Block block,
            int itemIndex,
            int stateChangeIndex,
            @NonNull StateChange stateChange) {
        public long blockNumber() {
            return BlockWatcher.blockNumberOf(block);
        }
    }

    /**
     * A transaction found in a block, with its result and the state changes it caused.
     *
     * @param block the block
     * @param body the transaction body
     * @param result the transaction result
     * @param stateChanges the state changes with the transaction's consensus timestamp
     */
    public record FoundTransaction(
            @NonNull Block block,
            @NonNull TransactionBody body,
            @NonNull TransactionResult result,
            @NonNull List<StateChange> stateChanges) {
        public long blockNumber() {
            return BlockWatcher.blockNumberOf(block);
        }
    }

    private BlockWatcher() {}

    /**
     * Starts watching the blocks produced by the spec's target network.
     *
     * @param spec the spec
     * @return the watcher
     */
    public static BlockWatcher start(@NonNull final HapiSpec spec) {
        final var watcher = new BlockWatcher();
        watcher.unsubscribe = BlockSourceFactory.blockSourceFor(spec).subscribe(watcher);
        return watcher;
    }

    @Override
    public void onNewBlock(@NonNull final Block block) {
        if (!block.items().isEmpty() && block.items().getFirst().hasBlockHeader()) {
            blocks.put(blockNumberOf(block), block);
        }
    }

    @Override
    public String name() {
        return "BlockWatcher";
    }

    @Override
    public void close() {
        unsubscribe.run();
    }

    /**
     * Waits for the first state change, in block order, matching the given predicate.
     *
     * @param test the predicate
     * @param timeout the maximum time to wait
     * @return the location of the matching state change
     */
    public StateChangeLocation awaitStateChange(
            @NonNull final Predicate<StateChange> test, @NonNull final Duration timeout) {
        return await(block -> findStateChange(block, test), timeout, "state change");
    }

    /**
     * Waits for the transaction with the given id to appear in a block.
     *
     * @param transactionId the transaction id
     * @param timeout the maximum time to wait
     * @return the transaction, its result, and its state changes
     */
    public FoundTransaction awaitTransaction(
            @NonNull final TransactionID transactionId, @NonNull final Duration timeout) {
        return await(block -> findTransaction(block, transactionId), timeout, "transaction " + transactionId);
    }

    private <T> T await(
            @NonNull final Function<Block, Optional<T>> finder,
            @NonNull final Duration timeout,
            @NonNull final String description) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (final var block : blocks.values()) {
                final var found = finder.apply(block);
                if (found.isPresent()) {
                    return found.get();
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for " + description, e);
            }
        }
        throw new AssertionError("Timed out after " + timeout + " waiting for " + description);
    }

    private static Optional<StateChangeLocation> findStateChange(
            @NonNull final Block block, @NonNull final Predicate<StateChange> test) {
        final var items = block.items();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).hasStateChanges()) {
                final var changes = items.get(i).stateChangesOrThrow().stateChanges();
                for (int j = 0; j < changes.size(); j++) {
                    if (test.test(changes.get(j))) {
                        return Optional.of(new StateChangeLocation(block, i, j, changes.get(j)));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<FoundTransaction> findTransaction(
            @NonNull final Block block, @NonNull final TransactionID transactionId) {
        final var items = block.items();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).hasSignedTransaction()) {
                continue;
            }
            final var body = bodyOf(items.get(i));
            if (body == null || !transactionId.equals(body.transactionID())) {
                continue;
            }
            final var result = nextResult(items, i);
            if (result == null) {
                return Optional.empty();
            }
            final List<StateChange> stateChanges = new ArrayList<>();
            for (final var item : items) {
                if (item.hasStateChanges()
                        && result.consensusTimestamp()
                                .equals(item.stateChangesOrThrow().consensusTimestamp())) {
                    stateChanges.addAll(item.stateChangesOrThrow().stateChanges());
                }
            }
            return Optional.of(new FoundTransaction(block, body, result, stateChanges));
        }
        return Optional.empty();
    }

    private static @Nullable TransactionResult nextResult(@NonNull final List<BlockItem> items, final int from) {
        for (int k = from + 1; k < items.size(); k++) {
            if (items.get(k).hasTransactionResult()) {
                return items.get(k).transactionResultOrThrow();
            }
            if (items.get(k).hasSignedTransaction()) {
                return null;
            }
        }
        return null;
    }

    private static @Nullable TransactionBody bodyOf(@NonNull final BlockItem item) {
        try {
            final var signed = SignedTransaction.PROTOBUF.parse(item.signedTransactionOrThrow());
            return TransactionBody.PROTOBUF.parse(signed.bodyBytes());
        } catch (ParseException e) {
            return null;
        }
    }

    private static long blockNumberOf(@NonNull final Block block) {
        return requireNonNull(block.items().getFirst().blockHeader()).number();
    }
}
