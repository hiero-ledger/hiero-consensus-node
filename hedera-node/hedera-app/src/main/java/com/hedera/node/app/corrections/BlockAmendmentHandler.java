// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.corrections;

import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_BLOCK_AMENDMENT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AMENDMENT_BLOCK_NUMBER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AMENDMENT_OPERATION;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.network.BlockAmendment;
import com.hedera.hapi.node.network.BlockAmendmentOperation;
import com.hedera.hapi.node.network.BlockAmendmentTransactionBody;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handles {@code BlockAmendment} transactions, which amend the block stream record of one or more past blocks.
 *
 * <p>A consensus node keeps no historical blocks, so it cannot verify that an amendment is correct. It only
 * verifies that each amendment is well-formed and targets a distinct past block. A successful transaction changes
 * no state; its signed transaction and result in the block stream are the amendment of record, which Block Nodes
 * attach, with a proof, to every amended block. The transaction succeeds or fails as a whole.
 *
 * <p>Every position in an amendment refers to the original items of the amended block, so this handler rejects
 * any amendment that targets the same original item, state change, or insertion point twice, or that combines a
 * replace or remove of an item with an edit of that same item's state changes.
 */
@Singleton
public class BlockAmendmentHandler implements TransactionHandler {
    /**
     * The block item kinds an amendment may insert or use as a replacement. Structural items (headers, footers,
     * proofs) and stream-level items (record files, filtered or redacted items, amendments) are never permitted.
     */
    private static final Set<BlockItem.ItemOneOfType> AMENDABLE_ITEM_KINDS = EnumSet.of(
            BlockItem.ItemOneOfType.SIGNED_TRANSACTION,
            BlockItem.ItemOneOfType.TRANSACTION_RESULT,
            BlockItem.ItemOneOfType.TRANSACTION_OUTPUT,
            BlockItem.ItemOneOfType.STATE_CHANGES,
            BlockItem.ItemOneOfType.TRACE_DATA);

    /**
     * The kinds of position an amendment operation can target. Inserts target gaps between original elements;
     * replaces and removes target the original elements themselves.
     */
    private enum TargetType {
        ITEM,
        AFTER_ITEM,
        STATE_CHANGE,
        BEFORE_STATE_CHANGE
    }

    private record Target(@NonNull TargetType type, int itemIndex, int stateChangeIndex) {}

    /**
     * The index of the block header in every block.
     */
    private static final int BLOCK_HEADER_INDEX = 0;

    @Inject
    public BlockAmendmentHandler() {
        // Dagger2
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        validate(context.body().blockAmendmentOrThrow());
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) {
        requireNonNull(context);
        // Only the payer signature is required, and privileged authorization is enforced by the workflow
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var op = context.body().blockAmendmentOrThrow();
        final long currentBlockNumber = currentBlockNumber(context);
        for (final var amendment : op.amendments()) {
            // Each amended block must be strictly before the block containing this transaction; block_number is a
            // uint64, so compare unsigned to reject values that would otherwise read as negative
            if (Long.compareUnsigned(amendment.blockNumber(), currentBlockNumber) >= 0) {
                throw new HandleException(INVALID_AMENDMENT_BLOCK_NUMBER);
            }
        }
    }

    /**
     * Returns the number of the block stream block being built. Amendments are addressed by block stream block
     * number, which differs from the record stream block number while both streams are produced, so this reads the
     * {@link BlockStreamInfo} singleton rather than {@link HandleContext#blockRecordInfo()}. That singleton is
     * updated at the end of each block, so during a block it describes the previous one.
     */
    private static long currentBlockNumber(@NonNull final HandleContext context) {
        if (!(context.savepointStack() instanceof State state)) {
            throw new IllegalStateException("Savepoint stack does not expose state");
        }
        final var blockStreamInfo = state.getReadableStates(BlockStreamService.NAME)
                .<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID)
                .get();
        // Before the first block completes there is no past block to amend
        return blockStreamInfo == null ? 0 : blockStreamInfo.blockNumber() + 1;
    }

    /**
     * Validates that a block amendment transaction is well-formed, independent of any state.
     *
     * @param op the block amendment transaction
     * @throws PreCheckException if the transaction amends no block or the same block twice, or if any of its
     * amendments is empty, malformed, or internally conflicting
     */
    static void validate(@NonNull final BlockAmendmentTransactionBody op) throws PreCheckException {
        if (op.amendments().isEmpty()) {
            throw new PreCheckException(EMPTY_BLOCK_AMENDMENT);
        }
        final Set<Long> blockNumbers = new HashSet<>();
        for (final var amendment : op.amendments()) {
            // Two amendments of the same block in one transaction would have no defined order
            if (!blockNumbers.add(amendment.blockNumber())) {
                throw new PreCheckException(INVALID_AMENDMENT_BLOCK_NUMBER);
            }
            validate(amendment);
        }
    }

    /**
     * Validates that an amendment of a single block is well-formed, independent of any state.
     *
     * @param amendment the amendment
     * @throws PreCheckException if the amendment is empty, malformed, or internally conflicting
     */
    private static void validate(@NonNull final BlockAmendment amendment) throws PreCheckException {
        // An amendment that only voids previous amendments restores the original block, so it is not empty
        if (amendment.operations().isEmpty() && !amendment.voidPreviousAmendments()) {
            throw new PreCheckException(EMPTY_BLOCK_AMENDMENT);
        }
        final Set<Target> targets = new HashSet<>();
        final Set<Integer> replacedOrRemovedItems = new HashSet<>();
        final Set<Integer> itemsWithStateChangeEdits = new HashSet<>();
        for (final var operation : amendment.operations()) {
            final var target = validateOperation(operation);
            // Item 0 is always the block header, which may only have items inserted after it
            if (target.type() != TargetType.AFTER_ITEM && target.itemIndex() == BLOCK_HEADER_INDEX) {
                throw new PreCheckException(INVALID_AMENDMENT_OPERATION);
            }
            if (!targets.add(target)) {
                throw new PreCheckException(INVALID_AMENDMENT_OPERATION);
            }
            switch (target.type()) {
                case ITEM -> replacedOrRemovedItems.add(target.itemIndex());
                case STATE_CHANGE, BEFORE_STATE_CHANGE -> itemsWithStateChangeEdits.add(target.itemIndex());
                case AFTER_ITEM -> {
                    // Inserting after an item never conflicts with edits to that item
                }
            }
        }
        for (final var itemIndex : replacedOrRemovedItems) {
            if (itemsWithStateChangeEdits.contains(itemIndex)) {
                throw new PreCheckException(INVALID_AMENDMENT_OPERATION);
            }
        }
    }

    private static Target validateOperation(@NonNull final BlockAmendmentOperation operation) throws PreCheckException {
        return switch (operation.operation().kind()) {
            case UNSET -> throw new PreCheckException(INVALID_AMENDMENT_OPERATION);
            case INSERT_BLOCK_ITEMS -> {
                final var insert = operation.insertBlockItemsOrThrow();
                validateBlockItems(insert.blockItems());
                yield new Target(TargetType.AFTER_ITEM, insert.afterItemIndex(), 0);
            }
            case REPLACE_BLOCK_ITEM -> {
                final var replace = operation.replaceBlockItemOrThrow();
                validateBlockItem(replace.blockItem());
                yield new Target(TargetType.ITEM, replace.itemIndex(), 0);
            }
            case REMOVE_BLOCK_ITEM ->
                new Target(TargetType.ITEM, operation.removeBlockItemOrThrow().itemIndex(), 0);
            case INSERT_STATE_CHANGES -> {
                final var insert = operation.insertStateChangesOrThrow();
                validateStateChanges(insert.stateChanges());
                yield new Target(TargetType.BEFORE_STATE_CHANGE, insert.itemIndex(), insert.beforeStateChangeIndex());
            }
            case REPLACE_STATE_CHANGE -> {
                final var replace = operation.replaceStateChangeOrThrow();
                validateStateChange(replace.stateChange());
                yield new Target(TargetType.STATE_CHANGE, replace.itemIndex(), replace.stateChangeIndex());
            }
            case REMOVE_STATE_CHANGE -> {
                final var remove = operation.removeStateChangeOrThrow();
                yield new Target(TargetType.STATE_CHANGE, remove.itemIndex(), remove.stateChangeIndex());
            }
        };
    }

    private static void validateBlockItems(@NonNull final List<Bytes> items) throws PreCheckException {
        if (items.isEmpty()) {
            throw new PreCheckException(INVALID_AMENDMENT_OPERATION);
        }
        for (final var item : items) {
            validateBlockItem(item);
        }
    }

    private static void validateBlockItem(@NonNull final Bytes serializedItem) throws PreCheckException {
        final BlockItem item;
        try {
            item = BlockItem.PROTOBUF.parseStrict(serializedItem);
        } catch (ParseException e) {
            throw new PreCheckException(INVALID_AMENDMENT_OPERATION);
        }
        if (!AMENDABLE_ITEM_KINDS.contains(item.item().kind())) {
            throw new PreCheckException(INVALID_AMENDMENT_OPERATION);
        }
    }

    private static void validateStateChanges(@NonNull final List<Bytes> stateChanges) throws PreCheckException {
        if (stateChanges.isEmpty()) {
            throw new PreCheckException(INVALID_AMENDMENT_OPERATION);
        }
        for (final var stateChange : stateChanges) {
            validateStateChange(stateChange);
        }
    }

    private static void validateStateChange(@NonNull final Bytes serializedStateChange) throws PreCheckException {
        final StateChange stateChange;
        try {
            stateChange = StateChange.PROTOBUF.parseStrict(serializedStateChange);
        } catch (ParseException e) {
            throw new PreCheckException(INVALID_AMENDMENT_OPERATION);
        }
        if (stateChange.changeOperation().kind() == StateChange.ChangeOperationOneOfType.UNSET) {
            throw new PreCheckException(INVALID_AMENDMENT_OPERATION);
        }
    }
}
