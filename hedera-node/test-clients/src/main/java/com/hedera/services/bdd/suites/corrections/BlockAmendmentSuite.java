// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.corrections;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACCOUNTS;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.support.amendments.BlockAmendmentReplayer.amendedItems;
import static com.hedera.services.bdd.junit.support.amendments.BlockAmendmentReplayer.amendmentsOf;
import static com.hedera.services.bdd.junit.support.amendments.BlockAmendmentReplayer.originalItems;
import static com.hedera.services.bdd.junit.support.amendments.BlockAmendmentReplayer.stateChangesInReplayOrder;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.blockAmendment;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_TRANSACTION_IN_BLACKLIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EMPTY_BLOCK_AMENDMENT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AMENDMENT_BLOCK_NUMBER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AMENDMENT_OPERATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.network.BlockAmendment;
import com.hedera.hapi.node.network.BlockAmendmentOperation;
import com.hedera.hapi.node.network.InsertBlockItems;
import com.hedera.hapi.node.network.InsertStateChanges;
import com.hedera.hapi.node.network.RemoveBlockItem;
import com.hedera.hapi.node.network.RemoveStateChange;
import com.hedera.hapi.node.network.ReplaceBlockItem;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.support.amendments.BlockWatcher;
import com.hedera.services.bdd.junit.support.amendments.BlockWatcher.StateChangeLocation;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

/**
 * Tests the {@code BlockAmendment} transaction, which amends the block stream record of a past block.
 */
@DisplayName("Block amendments")
public class BlockAmendmentSuite {
    private static final Duration BLOCK_TIMEOUT = Duration.ofMinutes(2);
    private static final String CIVILIAN = "civilian";
    private static final String BATCH_OPERATOR = "batchOperator";

    @HapiTest
    @DisplayName("treasury and system admin may submit amendments")
    final Stream<DynamicTest> treasuryAndSystemAdminMaySubmitAmendments() {
        return hapiTest(
                blockAmendment(0L)
                        .withOperations(removeStateChange(1, 0))
                        .payingWith(GENESIS)
                        .hasKnownStatus(SUCCESS),
                blockAmendment(0L)
                        .withOperations(removeStateChange(1, 0))
                        .payingWith(SYSTEM_ADMIN)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("other payers may not submit amendments")
    final Stream<DynamicTest> otherPayersMayNotSubmitAmendments() {
        return hapiTest(
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                blockAmendment(0L)
                        .withOperations(removeStateChange(1, 0))
                        .payingWith(CIVILIAN)
                        .hasPrecheck(NOT_SUPPORTED),
                // A system account outside the API permission range passes ingest but fails at consensus
                blockAmendment(0L)
                        .withOperations(removeStateChange(1, 0))
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .hasKnownStatus(UNAUTHORIZED));
    }

    @HapiTest
    @DisplayName("amendments may not be inner transactions of an atomic batch")
    final Stream<DynamicTest> amendmentsMayNotBeBatched() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HUNDRED_HBARS),
                atomicBatch(blockAmendment(0L)
                                .withOperations(removeStateChange(1, 0))
                                .payingWith(GENESIS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST));
    }

    @HapiTest
    @DisplayName("an amendment must contain an operation or void previous amendments")
    final Stream<DynamicTest> amendmentMustNotBeEmpty() {
        return hapiTest(
                // A transaction that amends no block at all
                blockAmendment().payingWith(GENESIS).hasPrecheck(EMPTY_BLOCK_AMENDMENT),
                blockAmendment(0L).payingWith(GENESIS).hasPrecheck(EMPTY_BLOCK_AMENDMENT),
                // Voiding every previous amendment with no operations restores the original block
                blockAmendment(0L)
                        .voidingPreviousAmendments()
                        .payingWith(GENESIS)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("an amendment must target a block before the current block")
    final Stream<DynamicTest> amendmentMustTargetAPastBlock() {
        return hapiTest(
                blockAmendment(Long.MAX_VALUE)
                        .withOperations(removeStateChange(1, 0))
                        .payingWith(GENESIS)
                        .hasKnownStatus(INVALID_AMENDMENT_BLOCK_NUMBER),
                // The maximum uint64 block number reads as -1 in Java and must still be rejected
                blockAmendment(-1L)
                        .withOperations(removeStateChange(1, 0))
                        .payingWith(GENESIS)
                        .hasKnownStatus(INVALID_AMENDMENT_BLOCK_NUMBER));
    }

    @HapiTest
    @DisplayName("a transaction amending several blocks must amend each at most once, and fails as a whole")
    final Stream<DynamicTest> severalBlocksMustBeDistinctAndAllValid() {
        return hapiTest(
                // The same block twice
                blockAmendment(0L)
                        .withOperations(removeStateChange(1, 0))
                        .andAmending(0L)
                        .withOperations(removeStateChange(1, 1))
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_AMENDMENT_BLOCK_NUMBER),
                // An empty amendment of one block rejects the whole transaction
                blockAmendment(0L)
                        .withOperations(removeStateChange(1, 0))
                        .andAmending(1L)
                        .payingWith(GENESIS)
                        .hasPrecheck(EMPTY_BLOCK_AMENDMENT),
                // A malformed amendment of one block rejects the whole transaction
                blockAmendment(0L)
                        .withOperations(removeStateChange(1, 0))
                        .andAmending(1L)
                        .withOperations(removeBlockItem(0))
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_AMENDMENT_OPERATION),
                // An amendment of a block that is not in the past fails the whole transaction
                blockAmendment(0L)
                        .withOperations(removeStateChange(1, 0))
                        .andAmending(Long.MAX_VALUE)
                        .withOperations(removeStateChange(1, 0))
                        .payingWith(GENESIS)
                        .hasKnownStatus(INVALID_AMENDMENT_BLOCK_NUMBER));
    }

    @HapiTest
    @DisplayName("malformed or conflicting amendment operations are rejected")
    final Stream<DynamicTest> malformedOrConflictingOperationsAreRejected() {
        final var garbage = Bytes.wrap(new byte[] {(byte) 0xFF, 0x01});
        final var header = BlockItem.PROTOBUF.toBytes(
                BlockItem.newBuilder().blockHeader(BlockHeader.DEFAULT).build());
        final var result = BlockItem.PROTOBUF.toBytes(BlockItem.newBuilder()
                .transactionResult(TransactionResult.DEFAULT)
                .build());
        return hapiTest(
                // An operation with no kind set
                blockAmendment(0L)
                        .withOperations(BlockAmendmentOperation.DEFAULT)
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_AMENDMENT_OPERATION),
                // An unparseable block item
                blockAmendment(0L)
                        .withOperations(insertBlockItems(3, garbage))
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_AMENDMENT_OPERATION),
                // An empty list of items to insert
                blockAmendment(0L)
                        .withOperations(insertBlockItems(3))
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_AMENDMENT_OPERATION),
                // A structural block item that may never be inserted
                blockAmendment(0L)
                        .withOperations(insertBlockItems(3, header))
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_AMENDMENT_OPERATION),
                // Two operations targeting the same original state change
                blockAmendment(0L)
                        .withOperations(removeStateChange(4, 1), removeStateChange(4, 1))
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_AMENDMENT_OPERATION),
                // Replacing an item while also editing its state changes
                blockAmendment(0L)
                        .withOperations(replaceBlockItem(4, result), removeStateChange(4, 1))
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_AMENDMENT_OPERATION),
                // Removing the block header
                blockAmendment(0L)
                        .withOperations(removeBlockItem(0))
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_AMENDMENT_OPERATION),
                // Inserting after the header, and at distinct targets, is fine
                blockAmendment(0L)
                        .withOperations(insertBlockItems(0, result), removeStateChange(4, 1), removeStateChange(4, 2))
                        .payingWith(GENESIS)
                        .hasKnownStatus(SUCCESS));
    }

    /**
     * Simulates a consensus node that omitted a state change from block N. A first amendment re-inserts the change
     * at the wrong position; a second amendment voids it and restates the edit at the original position. Both are
     * extracted from the later blocks that contain them, exactly as a Block Node would extract them, and applied in
     * consensus order. The result is block N's exact original state change sequence, so the amended block replays to
     * the same state root that block N+1 signs (which the end-of-run {@code StateChangesValidator} verifies for the
     * original block). The bad amendment alone, or the change inserted at any other position, does not restore it.
     */
    @HapiTest
    @DisplayName("a corrected amendment that voids a bad one restores a dropped state change at its exact position")
    final Stream<DynamicTest> correctedAmendmentRestoresDroppedStateChangeAtItsExactPosition() {
        final var watcher = new AtomicReference<BlockWatcher>();
        final var dropped = new AtomicReference<StateChangeLocation>();
        return hapiTest(
                doingContextual(spec -> watcher.set(BlockWatcher.start(spec))),
                cryptoCreate("amendmentTarget"),
                doingContextual(spec -> {
                    final var location = awaitAccountUpdate(spec, watcher.get(), "amendmentTarget");
                    assertTrue(
                            stateChangeCount(location) > 1,
                            "Need another state change in the same item to misplace the dropped one");
                    dropped.set(location);
                }),
                // A bad amendment: the right change at the wrong position
                blockAmendment(spec -> dropped.get().blockNumber())
                        .withOperations(spec -> List.of(insertStateChanges(
                                dropped.get().itemIndex(),
                                wrongPosition(dropped.get()),
                                dropped.get().stateChange())))
                        .payingWith(GENESIS)
                        .via("badAmendment"),
                // The correction voids the bad amendment and restates the edit against the original block
                blockAmendment(spec -> dropped.get().blockNumber())
                        .voidingPreviousAmendments()
                        .withOperations(spec -> List.of(reinsert(dropped.get())))
                        .payingWith(GENESIS)
                        .via("correctedAmendment"),
                doingContextual(spec -> {
                    try (final var blocks = watcher.get()) {
                        final var location = dropped.get();
                        // Extract both amendments from the later blocks, as a Block Node would
                        final var bad = blocks.awaitTransaction(txnId(spec, "badAmendment"), BLOCK_TIMEOUT);
                        final var corrected = blocks.awaitTransaction(txnId(spec, "correctedAmendment"), BLOCK_TIMEOUT);
                        for (final var found : List.of(bad, corrected)) {
                            assertEquals(
                                    com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS,
                                    found.result().status());
                            assertTrue(
                                    found.blockNumber() > location.blockNumber(), "Amendment must be in a later block");
                        }
                        final var inConsensusOrder = amendmentsOf(
                                location.blockNumber(),
                                List.of(
                                        bad.body().blockAmendmentOrThrow(),
                                        corrected.body().blockAmendmentOrThrow()));

                        final var original = originalItems(location.block().items());
                        final var broken = withoutStateChange(original, location);
                        final var originalOrder = stateChangesInReplayOrder(original);
                        assertNotEquals(originalOrder, stateChangesInReplayOrder(broken));

                        // The bad amendment alone does not restore the original replay order
                        assertNotEquals(
                                originalOrder,
                                stateChangesInReplayOrder(amendedItems(broken, inConsensusOrder.subList(0, 1))));

                        // With the correction voiding it, the exact original replay order is restored
                        assertEquals(originalOrder, stateChangesInReplayOrder(amendedItems(broken, inConsensusOrder)));

                        // The same change inserted at any other position does not restore it
                        for (int position = 0; position < stateChangeCount(location); position++) {
                            if (position == location.stateChangeIndex()) {
                                continue;
                            }
                            final var misplaced = BlockAmendment.newBuilder()
                                    .blockNumber(location.blockNumber())
                                    .operations(
                                            insertStateChanges(location.itemIndex(), position, location.stateChange()))
                                    .build();
                            assertNotEquals(
                                    originalOrder,
                                    stateChangesInReplayOrder(amendedItems(broken, List.of(misplaced))),
                                    "Inserting at position " + position + " should not restore the original order");
                        }
                    }
                }));
    }

    /**
     * Simulates a consensus node that omitted a state change from each of two different blocks. A single amendment
     * transaction restores both. A Block Node attaches that one transaction, with its proof, to both blocks, and each
     * block takes only its own amendment from it; each amended block then has its exact original state change
     * sequence.
     */
    @HapiTest
    @DisplayName("one amendment transaction restores dropped state changes in two different blocks")
    final Stream<DynamicTest> oneTransactionRestoresDroppedStateChangesInTwoBlocks() {
        final var watcher = new AtomicReference<BlockWatcher>();
        final var firstDropped = new AtomicReference<StateChangeLocation>();
        final var secondDropped = new AtomicReference<StateChangeLocation>();
        return hapiTest(
                doingContextual(spec -> watcher.set(BlockWatcher.start(spec))),
                cryptoCreate("firstTarget"),
                // Waiting for the first block to be written guarantees the next transaction lands in a later block
                doingContextual(spec -> firstDropped.set(awaitAccountUpdate(spec, watcher.get(), "firstTarget"))),
                cryptoCreate("secondTarget"),
                doingContextual(spec -> secondDropped.set(awaitAccountUpdate(spec, watcher.get(), "secondTarget"))),
                blockAmendment(spec -> firstDropped.get().blockNumber())
                        .withOperations(spec -> List.of(reinsert(firstDropped.get())))
                        .andAmending(spec -> secondDropped.get().blockNumber())
                        .withOperations(spec -> List.of(reinsert(secondDropped.get())))
                        .payingWith(GENESIS)
                        .via("twoBlockAmendment"),
                doingContextual(spec -> {
                    try (final var blocks = watcher.get()) {
                        final var first = firstDropped.get();
                        final var second = secondDropped.get();
                        assertTrue(
                                second.blockNumber() > first.blockNumber(),
                                "The dropped state changes must be in different blocks");
                        // Extract the amendment from the later block, as a Block Node would
                        final var found = blocks.awaitTransaction(txnId(spec, "twoBlockAmendment"), BLOCK_TIMEOUT);
                        assertEquals(
                                com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS,
                                found.result().status());
                        assertTrue(found.blockNumber() > second.blockNumber(), "Amendment must be in a later block");
                        final var transactions = List.of(found.body().blockAmendmentOrThrow());
                        for (final var location : List.of(first, second)) {
                            final var amendments = amendmentsOf(location.blockNumber(), transactions);
                            assertEquals(1, amendments.size(), "Each block must have exactly one amendment");
                            final var original = originalItems(location.block().items());
                            final var broken = withoutStateChange(original, location);
                            final var originalOrder = stateChangesInReplayOrder(original);
                            assertNotEquals(originalOrder, stateChangesInReplayOrder(broken));
                            assertEquals(
                                    originalOrder,
                                    stateChangesInReplayOrder(amendedItems(broken, amendments)),
                                    "Block " + location.blockNumber() + " must replay in its original order");
                        }
                    }
                }));
    }

    /**
     * Waits for the first block stream update of the named account, which is its creation.
     */
    private static StateChangeLocation awaitAccountUpdate(
            @NonNull final HapiSpec spec, @NonNull final BlockWatcher watcher, @NonNull final String account) {
        final var accountId = toPbj(spec.registry().getAccountID(account));
        return watcher.awaitStateChange(change -> isAccountUpdate(change, accountId), BLOCK_TIMEOUT);
    }

    /**
     * Returns an operation inserting a dropped state change back at its original position.
     */
    private static BlockAmendmentOperation reinsert(@NonNull final StateChangeLocation location) {
        return insertStateChanges(location.itemIndex(), location.stateChangeIndex(), location.stateChange());
    }

    private static TransactionID txnId(@NonNull final HapiSpec spec, @NonNull final String name) {
        return protoToPbj(spec.registry().getTxnId(name), TransactionID.class);
    }

    private static int stateChangeCount(@NonNull final StateChangeLocation location) {
        return location.block()
                .items()
                .get(location.itemIndex())
                .stateChangesOrThrow()
                .stateChanges()
                .size();
    }

    /**
     * Returns a valid insertion position, in the block with the change dropped, other than the original one.
     */
    private static int wrongPosition(@NonNull final StateChangeLocation location) {
        return location.stateChangeIndex() == 0 ? 1 : 0;
    }

    private static boolean isAccountUpdate(@NonNull final StateChange change, @NonNull final AccountID accountId) {
        return change.stateId() == STATE_ID_ACCOUNTS.protoOrdinal()
                && change.hasMapUpdate()
                && accountId.equals(change.mapUpdateOrThrow().keyOrThrow().accountIdKey());
    }

    /**
     * Returns the given original block items with one state change removed, simulating a consensus node that
     * applied the change to state but omitted it from the block.
     */
    private static List<BlockItem> withoutStateChange(
            @NonNull final List<BlockItem> original, @NonNull final StateChangeLocation location) {
        final List<BlockItem> broken = new ArrayList<>(original);
        final var stateChanges = original.get(location.itemIndex()).stateChangesOrThrow();
        final List<StateChange> remaining = new ArrayList<>(stateChanges.stateChanges());
        remaining.remove(location.stateChangeIndex());
        broken.set(
                location.itemIndex(),
                BlockItem.newBuilder()
                        .stateChanges(new StateChanges(stateChanges.consensusTimestamp(), remaining))
                        .build());
        return broken;
    }

    private static BlockAmendmentOperation insertStateChanges(
            final int itemIndex, final int beforeStateChangeIndex, @NonNull final StateChange stateChange) {
        return BlockAmendmentOperation.newBuilder()
                .insertStateChanges(InsertStateChanges.newBuilder()
                        .itemIndex(itemIndex)
                        .beforeStateChangeIndex(beforeStateChangeIndex)
                        .stateChanges(List.of(StateChange.PROTOBUF.toBytes(stateChange)))
                        .build())
                .build();
    }

    private static BlockAmendmentOperation insertBlockItems(final int afterItemIndex, @NonNull final Bytes... items) {
        return BlockAmendmentOperation.newBuilder()
                .insertBlockItems(InsertBlockItems.newBuilder()
                        .afterItemIndex(afterItemIndex)
                        .blockItems(List.of(items))
                        .build())
                .build();
    }

    private static BlockAmendmentOperation replaceBlockItem(final int itemIndex, @NonNull final Bytes item) {
        return BlockAmendmentOperation.newBuilder()
                .replaceBlockItem(ReplaceBlockItem.newBuilder()
                        .itemIndex(itemIndex)
                        .blockItem(item)
                        .build())
                .build();
    }

    private static BlockAmendmentOperation removeBlockItem(final int itemIndex) {
        return BlockAmendmentOperation.newBuilder()
                .removeBlockItem(
                        RemoveBlockItem.newBuilder().itemIndex(itemIndex).build())
                .build();
    }

    private static BlockAmendmentOperation removeStateChange(final int itemIndex, final int stateChangeIndex) {
        return BlockAmendmentOperation.newBuilder()
                .removeStateChange(RemoveStateChange.newBuilder()
                        .itemIndex(itemIndex)
                        .stateChangeIndex(stateChangeIndex)
                        .build())
                .build();
    }
}
