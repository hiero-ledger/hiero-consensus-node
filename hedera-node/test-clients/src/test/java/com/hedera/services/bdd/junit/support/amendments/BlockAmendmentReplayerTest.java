// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.amendments;

import static com.hedera.services.bdd.junit.support.amendments.BlockAmendmentReplayer.amendedItems;
import static com.hedera.services.bdd.junit.support.amendments.BlockAmendmentReplayer.amendmentsOf;
import static com.hedera.services.bdd.junit.support.amendments.BlockAmendmentReplayer.stateChangesInReplayOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.input.EventHeader;
import com.hedera.hapi.block.stream.input.RoundHeader;
import com.hedera.hapi.block.stream.output.BlockFooter;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapDeleteChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.network.BlockAmendment;
import com.hedera.hapi.node.network.BlockAmendmentOperation;
import com.hedera.hapi.node.network.BlockAmendmentTransactionBody;
import com.hedera.hapi.node.network.InsertBlockItems;
import com.hedera.hapi.node.network.InsertStateChanges;
import com.hedera.hapi.node.network.RemoveBlockItem;
import com.hedera.hapi.node.network.RemoveStateChange;
import com.hedera.hapi.node.network.ReplaceBlockItem;
import com.hedera.hapi.node.network.ReplaceStateChange;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockAmendmentReplayerTest {
    private static final Timestamp NOW = new Timestamp(1_234_567L, 0);
    private static final long BLOCK_NUMBER = 42;

    // Distinct, recognizable state changes
    private static final StateChange C0 = change(100);
    private static final StateChange C1 = change(101);
    private static final StateChange C2 = change(102);
    private static final StateChange D0 = change(200);
    private static final StateChange D1 = change(201);
    private static final StateChange X = change(900);
    private static final StateChange Y = change(901);
    private static final StateChange Z = change(902);

    /**
     * The worked example from the HIP, block 42:
     * <pre>
     * 0 BlockHeader
     * 1 RoundHeader
     * 2 EventHeader
     * 3 SignedTransaction (A)
     * 4 TransactionResult (A)
     * 5 StateChanges [c0, c1, c2]
     * 6 SignedTransaction (B)
     * 7 TransactionResult (B)
     * 8 StateChanges [d0, d1]
     * 9 BlockFooter
     * </pre>
     */
    private static final List<BlockItem> ORIGINAL = List.of(
            BlockItem.newBuilder()
                    .blockHeader(BlockHeader.newBuilder().number(BLOCK_NUMBER).build())
                    .build(),
            BlockItem.newBuilder().roundHeader(RoundHeader.DEFAULT).build(),
            BlockItem.newBuilder().eventHeader(EventHeader.DEFAULT).build(),
            signedTxn("A"),
            result(ResponseCodeEnum.SUCCESS),
            stateChanges(C0, C1, C2),
            signedTxn("B"),
            result(ResponseCodeEnum.SUCCESS),
            stateChanges(D0, D1),
            BlockItem.newBuilder().blockFooter(BlockFooter.DEFAULT).build());

    @Test
    void amendmentsComposeAgainstOriginalPositionsWithoutShifting() {
        final var first = amendment(insertStateChanges(5, 1, X), insertBlockItems(7, stateChanges(Y)));
        final var second = amendment(insertStateChanges(5, 1, Z), removeStateChange(8, 0));

        final var amended = amendedItems(ORIGINAL, List.of(first, second));

        assertEquals(stateChanges(C0, X, Z, C1, C2), amended.get(5));
        assertEquals(stateChanges(Y), amended.get(8));
        assertEquals(stateChanges(D1), amended.get(9));
        assertEquals(List.of(C0, X, Z, C1, C2, Y, D1), stateChangesInReplayOrder(amended));
    }

    @Test
    void secondAmendmentUsesTheSamePositionsWithOrWithoutTheFirst() {
        final var second = amendment(insertStateChanges(5, 1, Z), removeStateChange(8, 0));

        final var alone = amendedItems(ORIGINAL, List.of(second));

        assertEquals(List.of(C0, Z, C1, C2, D1), stateChangesInReplayOrder(alone));
    }

    @Test
    void anythingAfterTheFooterIsIgnored() {
        final var stored = new java.util.ArrayList<>(ORIGINAL);
        stored.add(BlockItem.newBuilder().blockProof(BlockProof.DEFAULT).build());

        assertEquals(ORIGINAL, amendedItems(stored, List.of()));
    }

    @Test
    void insertsAtEndOfAStateChangesItemAppend() {
        final var amended = amendedItems(ORIGINAL, List.of(amendment(insertStateChanges(5, 3, X))));

        assertEquals(stateChanges(C0, C1, C2, X), amended.get(5));
    }

    @Test
    void latestReplaceOrRemoveOfTheSameElementWins() {
        final var replace = amendment(replaceStateChange(5, 1, X));
        final var remove = amendment(removeStateChange(5, 1));
        final var replaceAgain = amendment(replaceStateChange(5, 1, Y));

        assertEquals(
                stateChanges(C0, C2),
                amendedItems(ORIGINAL, List.of(replace, remove)).get(5));
        assertEquals(
                stateChanges(C0, Y, C2),
                amendedItems(ORIGINAL, List.of(replace, remove, replaceAgain)).get(5));
    }

    @Test
    void itemLevelReplaceTakesPrecedenceOverStateChangeEdits() {
        final var edit = amendment(insertStateChanges(5, 0, X));
        final var replace = amendment(replaceBlockItem(5, stateChanges(Y)));

        final var amended = amendedItems(ORIGINAL, List.of(replace, edit));

        assertEquals(stateChanges(Y), amended.get(5));
    }

    @Test
    void removedItemsDisappearButInsertsAfterThemRemain() {
        final var amended =
                amendedItems(ORIGINAL, List.of(amendment(removeBlockItem(5), insertBlockItems(5, stateChanges(X)))));

        assertEquals(List.of(X, D0, D1), stateChangesInReplayOrder(amended));
    }

    @Test
    void voidingDiscardsEveryEarlierAmendmentAndLaterOnesComposeOnTop() {
        final var bad = amendment(insertStateChanges(5, 0, X));
        final var alsoBad = amendment(removeStateChange(8, 0));
        // The correction restates the intended edit against the original block, without regard to the bad ones
        final var correction = voiding(insertStateChanges(5, 0, Y));
        final var later = amendment(insertStateChanges(5, 3, Z));

        final var amended = amendedItems(ORIGINAL, List.of(bad, alsoBad, correction, later));

        assertEquals(stateChanges(Y, C0, C1, C2, Z), amended.get(5));
        assertEquals(stateChanges(D0, D1), amended.get(8));
    }

    @Test
    void voidingWithNoOperationsRestoresTheOriginalBlock() {
        final var bad = amendment(insertStateChanges(5, 0, X), removeBlockItem(4));

        assertEquals(ORIGINAL, amendedItems(ORIGINAL, List.of(bad, voiding())));
    }

    @Test
    void aRestatementSplitAcrossAmendmentsVoidsOnlyOnce() {
        final var bad = amendment(insertStateChanges(5, 0, X));
        final var firstPart = voiding(insertStateChanges(5, 1, Y));
        final var secondPart = amendment(removeStateChange(8, 0));

        final var amended = amendedItems(ORIGINAL, List.of(bad, firstPart, secondPart));

        assertEquals(List.of(C0, Y, C1, C2, D1), stateChangesInReplayOrder(amended));
    }

    @Test
    void anInvalidVoidingAmendmentIsIgnoredSoEarlierAmendmentsStand() {
        final var good = amendment(insertStateChanges(5, 0, X));
        // Item 4 is a transaction result, so this whole amendment, including its voiding, is ignored
        final var invalidVoiding = voiding(insertStateChanges(5, 0, Y), removeStateChange(4, 0));

        final var amended = amendedItems(ORIGINAL, List.of(good, invalidVoiding));

        assertEquals(stateChanges(X, C0, C1, C2), amended.get(5));
    }

    @Test
    void anAmendmentWithAnyInvalidOperationIsIgnoredEntirely() {
        // Item 4 is a transaction result, not a StateChanges item
        final var illTyped = amendment(insertStateChanges(5, 0, X), removeStateChange(4, 0));
        // Position 4 is past the end of item 5's three state changes
        final var outOfRange = amendment(insertStateChanges(5, 4, Y));
        // The footer may not be removed
        final var removesFooter = amendment(insertStateChanges(5, 0, Z), removeBlockItem(9));
        // Nothing may be inserted after the footer
        final var afterFooter = amendment(insertBlockItems(9, stateChanges(Z)));

        assertEquals(ORIGINAL, amendedItems(ORIGINAL, List.of(illTyped, outOfRange, removesFooter, afterFooter)));
    }

    @Test
    void aBlockWithoutAFooterIsRejected() {
        final var withoutFooter = ORIGINAL.subList(0, ORIGINAL.size() - 1);

        assertThrows(IllegalArgumentException.class, () -> amendedItems(withoutFooter, List.of()));
    }

    @Test
    void aBlockWithoutAHeaderIsRejected() {
        final var withoutHeader = ORIGINAL.subList(1, ORIGINAL.size());

        assertThrows(IllegalArgumentException.class, () -> amendedItems(withoutHeader, List.of()));
    }

    @Test
    void anAmendmentOfAnotherBlockIsRejected() {
        final var ofAnotherBlock = amendment(BLOCK_NUMBER + 1, insertStateChanges(5, 0, X));

        assertThrows(IllegalArgumentException.class, () -> amendedItems(ORIGINAL, List.of(ofAnotherBlock)));
    }

    @Test
    void eachBlockTakesOnlyItsOwnAmendmentFromEachTransaction() {
        final var ofThisBlock = amendment(insertStateChanges(5, 0, X));
        final var laterOfThisBlock = amendment(removeStateChange(8, 0));
        final var first = transaction(amendment(BLOCK_NUMBER - 1, removeStateChange(8, 1)), ofThisBlock);
        final var second = transaction(amendment(BLOCK_NUMBER + 1, removeStateChange(8, 1)));
        final var third = transaction(laterOfThisBlock, amendment(BLOCK_NUMBER - 2, removeBlockItem(4)));

        final var amendments = amendmentsOf(BLOCK_NUMBER, List.of(first, second, third));

        assertEquals(List.of(ofThisBlock, laterOfThisBlock), amendments);
        assertEquals(List.of(X, C0, C1, C2, D1), stateChangesInReplayOrder(amendedItems(ORIGINAL, amendments)));
    }

    @Test
    void voidingTheAmendmentsOfAnotherBlockDoesNotVoidThisBlocksAmendments() {
        final var first = transaction(amendment(insertStateChanges(5, 0, X)));
        // A correction of a different block, in the same transaction as a further edit of this block
        final var second =
                transaction(voiding(BLOCK_NUMBER + 1, removeStateChange(8, 1)), amendment(insertStateChanges(5, 3, Y)));

        final var amended = amendedItems(ORIGINAL, amendmentsOf(BLOCK_NUMBER, List.of(first, second)));

        assertEquals(stateChanges(X, C0, C1, C2, Y), amended.get(5));
    }

    private static StateChange change(final long accountNum) {
        return StateChange.newBuilder()
                .stateId(2)
                .mapDelete(new MapDeleteChange(MapChangeKey.newBuilder()
                        .accountIdKey(AccountID.newBuilder().accountNum(accountNum))
                        .build()))
                .build();
    }

    private static BlockItem stateChanges(final StateChange... changes) {
        return BlockItem.newBuilder()
                .stateChanges(new StateChanges(NOW, Arrays.asList(changes)))
                .build();
    }

    private static BlockItem signedTxn(final String label) {
        return BlockItem.newBuilder().signedTransaction(Bytes.wrap(label)).build();
    }

    private static BlockItem result(final ResponseCodeEnum status) {
        return BlockItem.newBuilder()
                .transactionResult(TransactionResult.newBuilder().status(status).build())
                .build();
    }

    private static BlockAmendmentTransactionBody transaction(final BlockAmendment... amendments) {
        return BlockAmendmentTransactionBody.newBuilder().amendments(amendments).build();
    }

    private static BlockAmendment amendment(final BlockAmendmentOperation... operations) {
        return amendment(BLOCK_NUMBER, operations);
    }

    private static BlockAmendment amendment(final long blockNumber, final BlockAmendmentOperation... operations) {
        return BlockAmendment.newBuilder()
                .blockNumber(blockNumber)
                .operations(operations)
                .build();
    }

    private static BlockAmendment voiding(final BlockAmendmentOperation... operations) {
        return voiding(BLOCK_NUMBER, operations);
    }

    private static BlockAmendment voiding(final long blockNumber, final BlockAmendmentOperation... operations) {
        return BlockAmendment.newBuilder()
                .blockNumber(blockNumber)
                .voidPreviousAmendments(true)
                .operations(operations)
                .build();
    }

    private static BlockAmendmentOperation insertStateChanges(
            final int itemIndex, final int before, final StateChange... changes) {
        return BlockAmendmentOperation.newBuilder()
                .insertStateChanges(InsertStateChanges.newBuilder()
                        .itemIndex(itemIndex)
                        .beforeStateChangeIndex(before)
                        .stateChanges(Arrays.stream(changes)
                                .map(StateChange.PROTOBUF::toBytes)
                                .toList())
                        .build())
                .build();
    }

    private static BlockAmendmentOperation replaceStateChange(
            final int itemIndex, final int index, final StateChange change) {
        return BlockAmendmentOperation.newBuilder()
                .replaceStateChange(ReplaceStateChange.newBuilder()
                        .itemIndex(itemIndex)
                        .stateChangeIndex(index)
                        .stateChange(StateChange.PROTOBUF.toBytes(change))
                        .build())
                .build();
    }

    private static BlockAmendmentOperation removeStateChange(final int itemIndex, final int index) {
        return BlockAmendmentOperation.newBuilder()
                .removeStateChange(RemoveStateChange.newBuilder()
                        .itemIndex(itemIndex)
                        .stateChangeIndex(index)
                        .build())
                .build();
    }

    private static BlockAmendmentOperation insertBlockItems(final int after, final BlockItem... items) {
        return BlockAmendmentOperation.newBuilder()
                .insertBlockItems(InsertBlockItems.newBuilder()
                        .afterItemIndex(after)
                        .blockItems(Arrays.stream(items)
                                .map(BlockItem.PROTOBUF::toBytes)
                                .toList())
                        .build())
                .build();
    }

    private static BlockAmendmentOperation replaceBlockItem(final int itemIndex, final BlockItem item) {
        return BlockAmendmentOperation.newBuilder()
                .replaceBlockItem(ReplaceBlockItem.newBuilder()
                        .itemIndex(itemIndex)
                        .blockItem(BlockItem.PROTOBUF.toBytes(item))
                        .build())
                .build();
    }

    private static BlockAmendmentOperation removeBlockItem(final int itemIndex) {
        return BlockAmendmentOperation.newBuilder()
                .removeBlockItem(
                        RemoveBlockItem.newBuilder().itemIndex(itemIndex).build())
                .build();
    }
}
