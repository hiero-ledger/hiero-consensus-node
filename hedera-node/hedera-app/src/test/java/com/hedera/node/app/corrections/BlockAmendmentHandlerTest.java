// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.corrections;

import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_BLOCK_AMENDMENT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AMENDMENT_BLOCK_NUMBER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AMENDMENT_OPERATION;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockFooter;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapDeleteChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.AccountID;
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
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.MapReadableStates;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockAmendmentHandlerTest {
    private static final Bytes GARBAGE = Bytes.wrap(new byte[] {(byte) 0xFF, 0x01});
    private static final Bytes RESULT_ITEM = BlockItem.PROTOBUF.toBytes(
            BlockItem.newBuilder().transactionResult(TransactionResult.DEFAULT).build());
    private static final Bytes STATE_CHANGES_ITEM = BlockItem.PROTOBUF.toBytes(BlockItem.newBuilder()
            .stateChanges(StateChanges.newBuilder().consensusTimestamp(new Timestamp(1, 0)))
            .build());
    private static final Bytes HEADER_ITEM = BlockItem.PROTOBUF.toBytes(
            BlockItem.newBuilder().blockHeader(BlockHeader.DEFAULT).build());
    private static final Bytes FOOTER_ITEM = BlockItem.PROTOBUF.toBytes(
            BlockItem.newBuilder().blockFooter(BlockFooter.DEFAULT).build());
    private static final Bytes STATE_CHANGE = StateChange.PROTOBUF.toBytes(StateChange.newBuilder()
            .stateId(2)
            .mapDelete(new MapDeleteChange(MapChangeKey.newBuilder()
                    .accountIdKey(AccountID.newBuilder().accountNum(1001))
                    .build()))
            .build());
    private static final Bytes UNSET_STATE_CHANGE =
            StateChange.PROTOBUF.toBytes(StateChange.newBuilder().stateId(2).build());

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private SavepointStackImpl stack;

    private final BlockAmendmentHandler subject = new BlockAmendmentHandler();

    @Test
    void rejectsTransactionThatAmendsNoBlock() {
        assertPureChecksFail(BlockAmendmentTransactionBody.DEFAULT, EMPTY_BLOCK_AMENDMENT);
    }

    @Test
    void rejectsEmptyAmendment() {
        assertPureChecksFail(
                transaction(BlockAmendment.newBuilder().blockNumber(1).build()), EMPTY_BLOCK_AMENDMENT);
    }

    @Test
    void rejectsEmptyAmendmentAlongsideValidOnes() {
        assertPureChecksFail(
                transaction(
                        blockAmendment(1, removeStateChange(1, 0)),
                        BlockAmendment.newBuilder().blockNumber(2).build()),
                EMPTY_BLOCK_AMENDMENT);
    }

    @Test
    void acceptsAmendmentThatOnlyVoidsPreviousAmendments() {
        assertPureChecksPass(transaction(BlockAmendment.newBuilder()
                .blockNumber(1)
                .voidPreviousAmendments(true)
                .build()));
    }

    @Test
    void acceptsAmendmentThatVoidsPreviousAmendmentsAndRestatesEdits() {
        assertPureChecksPass(transaction(BlockAmendment.newBuilder()
                .blockNumber(1)
                .voidPreviousAmendments(true)
                .operations(insertStateChanges(5, 0, STATE_CHANGE))
                .build()));
    }

    @Test
    void acceptsAmendmentsOfSeveralBlocks() {
        assertPureChecksPass(transaction(
                blockAmendment(1, removeStateChange(5, 1)),
                // Positions are per block, so the same target in a different block is not a duplicate
                blockAmendment(2, removeStateChange(5, 1)),
                BlockAmendment.newBuilder()
                        .blockNumber(3)
                        .voidPreviousAmendments(true)
                        .build()));
    }

    @Test
    void rejectsTwoAmendmentsOfTheSameBlock() {
        assertPureChecksFail(
                transaction(blockAmendment(1, removeStateChange(5, 1)), blockAmendment(1, removeStateChange(5, 2))),
                INVALID_AMENDMENT_BLOCK_NUMBER);
    }

    @Test
    void rejectsTransactionIfAnyAmendmentIsMalformed() {
        assertPureChecksFail(
                transaction(blockAmendment(1, removeStateChange(5, 1)), blockAmendment(2, removeBlockItem(0))),
                INVALID_AMENDMENT_OPERATION);
    }

    @Test
    void acceptsWellFormedOperationsAtDistinctTargets() {
        assertPureChecksPass(amendment(
                insertBlockItems(0, RESULT_ITEM, STATE_CHANGES_ITEM),
                replaceBlockItem(3, RESULT_ITEM),
                removeBlockItem(4),
                insertStateChanges(5, 0, STATE_CHANGE),
                replaceStateChange(5, 1, STATE_CHANGE),
                removeStateChange(5, 2),
                insertBlockItems(5, STATE_CHANGES_ITEM)));
    }

    @Test
    void rejectsOperationWithNoKind() {
        assertPureChecksFail(amendment(BlockAmendmentOperation.DEFAULT), INVALID_AMENDMENT_OPERATION);
    }

    @Test
    void rejectsUnparseableOrEmptyPayloads() {
        assertPureChecksFail(amendment(insertBlockItems(1, GARBAGE)), INVALID_AMENDMENT_OPERATION);
        assertPureChecksFail(amendment(insertBlockItems(1)), INVALID_AMENDMENT_OPERATION);
        assertPureChecksFail(amendment(replaceBlockItem(1, GARBAGE)), INVALID_AMENDMENT_OPERATION);
        assertPureChecksFail(amendment(insertStateChanges(1, 0, GARBAGE)), INVALID_AMENDMENT_OPERATION);
        assertPureChecksFail(amendment(insertStateChanges(1, 0)), INVALID_AMENDMENT_OPERATION);
        assertPureChecksFail(amendment(replaceStateChange(1, 0, GARBAGE)), INVALID_AMENDMENT_OPERATION);
        assertPureChecksFail(amendment(replaceStateChange(1, 0, UNSET_STATE_CHANGE)), INVALID_AMENDMENT_OPERATION);
    }

    @Test
    void rejectsStructuralBlockItems() {
        assertPureChecksFail(amendment(insertBlockItems(1, HEADER_ITEM)), INVALID_AMENDMENT_OPERATION);
        assertPureChecksFail(amendment(replaceBlockItem(1, FOOTER_ITEM)), INVALID_AMENDMENT_OPERATION);
    }

    @Test
    void rejectsDuplicateTargets() {
        assertPureChecksFail(
                amendment(removeStateChange(5, 1), replaceStateChange(5, 1, STATE_CHANGE)),
                INVALID_AMENDMENT_OPERATION);
        assertPureChecksFail(
                amendment(insertBlockItems(5, RESULT_ITEM), insertBlockItems(5, RESULT_ITEM)),
                INVALID_AMENDMENT_OPERATION);
        assertPureChecksFail(
                amendment(removeBlockItem(5), replaceBlockItem(5, RESULT_ITEM)), INVALID_AMENDMENT_OPERATION);
    }

    @Test
    void rejectsItemReplaceCombinedWithStateChangeEditsOfTheSameItem() {
        assertPureChecksFail(
                amendment(replaceBlockItem(5, STATE_CHANGES_ITEM), insertStateChanges(5, 0, STATE_CHANGE)),
                INVALID_AMENDMENT_OPERATION);
        assertPureChecksFail(amendment(removeStateChange(5, 0), removeBlockItem(5)), INVALID_AMENDMENT_OPERATION);
    }

    @Test
    void rejectsEditsOfTheBlockHeader() {
        assertPureChecksFail(amendment(removeBlockItem(0)), INVALID_AMENDMENT_OPERATION);
        assertPureChecksFail(amendment(replaceBlockItem(0, RESULT_ITEM)), INVALID_AMENDMENT_OPERATION);
        assertPureChecksFail(amendment(insertStateChanges(0, 0, STATE_CHANGE)), INVALID_AMENDMENT_OPERATION);
    }

    @Test
    void acceptsAmendmentOfAPastBlock() {
        givenCurrentBlock(10);
        given(handleContext.body()).willReturn(body(amendment(9, removeStateChange(1, 0))));

        assertDoesNotThrow(() -> subject.handle(handleContext));
    }

    @Test
    void acceptsAmendmentsOfSeveralPastBlocks() {
        givenCurrentBlock(10);
        given(handleContext.body())
                .willReturn(body(transaction(
                        blockAmendment(9, removeStateChange(1, 0)),
                        blockAmendment(0, removeStateChange(1, 0)),
                        blockAmendment(5, removeStateChange(1, 0)))));

        assertDoesNotThrow(() -> subject.handle(handleContext));
    }

    @Test
    void rejectsAmendmentOfTheCurrentOrAFutureBlock() {
        givenCurrentBlock(10);

        for (final long blockNumber : new long[] {10, 11, Long.MAX_VALUE, -1L}) {
            given(handleContext.body()).willReturn(body(amendment(blockNumber, removeStateChange(1, 0))));
            final var e = assertThrows(HandleException.class, () -> subject.handle(handleContext));
            assertThat(e.getStatus()).isEqualTo(INVALID_AMENDMENT_BLOCK_NUMBER);
        }
    }

    @Test
    void rejectsTransactionIfAnyAmendedBlockIsNotInThePast() {
        givenCurrentBlock(10);
        given(handleContext.body())
                .willReturn(body(transaction(
                        blockAmendment(9, removeStateChange(1, 0)), blockAmendment(10, removeStateChange(1, 0)))));

        final var e = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertThat(e.getStatus()).isEqualTo(INVALID_AMENDMENT_BLOCK_NUMBER);
    }

    @Test
    void rejectsEveryAmendmentBeforeTheFirstBlockCompletes() {
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getReadableStates(BlockStreamService.NAME))
                .willReturn(MapReadableStates.builder()
                        .state(new FunctionReadableSingletonState<BlockStreamInfo>(
                                BLOCK_STREAM_INFO_STATE_ID, "BLOCK_STREAM_INFO", () -> null))
                        .build());
        given(handleContext.body()).willReturn(body(amendment(0, removeStateChange(1, 0))));

        final var e = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertThat(e.getStatus()).isEqualTo(INVALID_AMENDMENT_BLOCK_NUMBER);
    }

    private void givenCurrentBlock(final long blockNumber) {
        // The BlockStreamInfo singleton describes the last completed block while the next one is being built
        final var info =
                BlockStreamInfo.newBuilder().blockNumber(blockNumber - 1).build();
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getReadableStates(BlockStreamService.NAME))
                .willReturn(MapReadableStates.builder()
                        .state(new FunctionReadableSingletonState<>(
                                BLOCK_STREAM_INFO_STATE_ID, "BLOCK_STREAM_INFO", () -> info))
                        .build());
    }

    private void assertPureChecksPass(final BlockAmendmentTransactionBody op) {
        given(pureChecksContext.body()).willReturn(body(op));
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    private void assertPureChecksFail(
            final BlockAmendmentTransactionBody op, final com.hedera.hapi.node.base.ResponseCodeEnum expected) {
        given(pureChecksContext.body()).willReturn(body(op));
        final var e = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(e.responseCode()).isEqualTo(expected);
    }

    private static TransactionBody body(final BlockAmendmentTransactionBody op) {
        return TransactionBody.newBuilder().blockAmendment(op).build();
    }

    private static BlockAmendmentTransactionBody amendment(final BlockAmendmentOperation... operations) {
        return amendment(1, operations);
    }

    private static BlockAmendmentTransactionBody amendment(
            final long blockNumber, final BlockAmendmentOperation... operations) {
        return transaction(blockAmendment(blockNumber, operations));
    }

    private static BlockAmendmentTransactionBody transaction(final BlockAmendment... amendments) {
        return BlockAmendmentTransactionBody.newBuilder().amendments(amendments).build();
    }

    private static BlockAmendment blockAmendment(final long blockNumber, final BlockAmendmentOperation... operations) {
        return BlockAmendment.newBuilder()
                .blockNumber(blockNumber)
                .operations(operations)
                .build();
    }

    private static BlockAmendmentOperation insertBlockItems(final int after, final Bytes... items) {
        return BlockAmendmentOperation.newBuilder()
                .insertBlockItems(InsertBlockItems.newBuilder()
                        .afterItemIndex(after)
                        .blockItems(List.of(items))
                        .build())
                .build();
    }

    private static BlockAmendmentOperation replaceBlockItem(final int index, final Bytes item) {
        return BlockAmendmentOperation.newBuilder()
                .replaceBlockItem(ReplaceBlockItem.newBuilder()
                        .itemIndex(index)
                        .blockItem(item)
                        .build())
                .build();
    }

    private static BlockAmendmentOperation removeBlockItem(final int index) {
        return BlockAmendmentOperation.newBuilder()
                .removeBlockItem(RemoveBlockItem.newBuilder().itemIndex(index).build())
                .build();
    }

    private static BlockAmendmentOperation insertStateChanges(
            final int index, final int before, final Bytes... changes) {
        return BlockAmendmentOperation.newBuilder()
                .insertStateChanges(InsertStateChanges.newBuilder()
                        .itemIndex(index)
                        .beforeStateChangeIndex(before)
                        .stateChanges(List.of(changes))
                        .build())
                .build();
    }

    private static BlockAmendmentOperation replaceStateChange(final int index, final int position, final Bytes change) {
        return BlockAmendmentOperation.newBuilder()
                .replaceStateChange(ReplaceStateChange.newBuilder()
                        .itemIndex(index)
                        .stateChangeIndex(position)
                        .stateChange(change)
                        .build())
                .build();
    }

    private static BlockAmendmentOperation removeStateChange(final int index, final int position) {
        return BlockAmendmentOperation.newBuilder()
                .removeStateChange(RemoveStateChange.newBuilder()
                        .itemIndex(index)
                        .stateChangeIndex(position)
                        .build())
                .build();
    }
}
