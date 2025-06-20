// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.hapi.block.stream.BlockItem.ItemOneOfType.TRACE_DATA;
import static com.hedera.hapi.block.stream.BlockItem.ItemOneOfType.TRANSACTION_OUTPUT;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.platform.event.EventTransaction;
import com.hedera.hapi.platform.event.TransactionGroupRole;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.BlockStreamAccess;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * Validates the structure of blocks.
 */
public class BlockContentsValidator implements BlockStreamValidator {
    private static final Logger logger = LogManager.getLogger(BlockContentsValidator.class);

    private static final int REASONABLE_NUM_PENDING_PROOFS_AT_FREEZE = 3;

    public static void main(String[] args) {
        final var node0Dir = Paths.get("hedera-node/test-clients")
                .resolve(workingDirFor(0, "hapi"))
                .toAbsolutePath()
                .normalize();
        final var validator = new BlockContentsValidator();
        final var blocks =
                BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(node0Dir.resolve("data/blockStreams/block-11.12.3"));
        validator.validateBlocks(blocks);
    }

    public static final Factory FACTORY = new Factory() {
        @NonNull
        @Override
        public BlockStreamValidator create(@NonNull final HapiSpec spec) {
            return new BlockContentsValidator();
        }
    };

    @Override
    public void validateBlocks(@NonNull final List<Block> blocks) {
        for (int i = 0, n = blocks.size(); i < n; i++) {
            try {
                validate(blocks.get(i), n - 1 - i);
            } catch (AssertionError err) {
                logger.error("Error validating block {}", blocks.get(i));
                throw err;
            }
        }
    }

    private void validate(Block block, final int blocksRemaining) {
        final var items = block.items();
        if (items.isEmpty()) {
            Assertions.fail("Block is empty");
        }

        if (items.size() <= 2) {
            Assertions.fail("Block contains insufficient number of block items");
        }

        // A block SHALL start with a `block_header`.
        validateBlockHeader(items.getFirst());

        validateRounds(items.subList(1, items.size() - 1));

        // A block SHALL end with a `block_proof`.
        if (blocksRemaining > REASONABLE_NUM_PENDING_PROOFS_AT_FREEZE) {
            validateBlockProof(items.getLast());
        }
    }

    private static void validateBlockHeader(final BlockItem item) {
        if (!item.hasBlockHeader()) {
            Assertions.fail("Block must start with a block header");
        }
    }

    private static void validateBlockProof(final BlockItem item) {
        if (!item.hasBlockProof()) {
            Assertions.fail("Block must end with a block proof");
        }
    }

    private void validateRounds(final List<BlockItem> roundItems) {
        int currentIndex = 0;
        while (currentIndex < roundItems.size()) {
            currentIndex = validateSingleRound(roundItems, currentIndex);
        }
    }

    /**
     * Validates a single round within a block, starting at the given index.
     * Returns the index of the next item after this round.
     */
    private int validateSingleRound(final List<BlockItem> items, int startIndex) {
        // Validate round header
        if (!items.get(startIndex).hasRoundHeader()) {
            logger.error("Expected round header at index {}, found: {}", startIndex, items.get(startIndex));
            Assertions.fail("Round must start with a round header");
        }

        int currentIndex = startIndex + 1;
        boolean hasEventOrStateChange = false;
        var isLatestParentBatchTxn = false;
        var latestParentFunction = HederaFunctionality.NONE;

        // Process items in this round until we hit the next round header or end of items
        while (currentIndex < items.size() && !items.get(currentIndex).hasRoundHeader()) {
            BlockItem item = items.get(currentIndex);

            if (item.hasEventHeader() || item.hasStateChanges()) {
                hasEventOrStateChange = true;
                currentIndex++;
            } else if (item.hasEventTransaction()) {
                latestParentFunction = functionOfTxn(item, currentIndex);
                if (isParent(item)) {
                    isLatestParentBatchTxn = latestParentFunction.equals(HederaFunctionality.ATOMIC_BATCH);
                }
                currentIndex = validateTransactionGroup(items, currentIndex);
            } else if (item.hasTransactionResult()) {
                if (!isLatestParentBatchTxn) {
                    logger.error(
                            "Found transaction result or output without preceding event transaction at index {}",
                            currentIndex);
                    Assertions.fail("Found transaction result or output without preceding event transaction at index "
                            + currentIndex);
                }
                currentIndex = validateResultOnlyGroup(items, currentIndex);
            } else {
                logger.error("Invalid item type at index {}: {}", currentIndex, item);
                Assertions.fail("Invalid item type at index " + currentIndex + ": " + item);
            }
        }

        if (!hasEventOrStateChange) {
            logger.error("Round starting at index {} has no event headers or state changes", startIndex);
            Assertions.fail("Round starting at index " + startIndex + " has no event headers or state changes");
        }

        return currentIndex;
    }

    /**
     * Checks if the given block item is a parent or starting parent transaction.
     *
     * @param item the block item to check
     * @return true if the item is a parent transaction, false otherwise
     */
    private static boolean isParent(final BlockItem item) {
        return item.eventTransactionOrThrow().transactionGroupRole() == TransactionGroupRole.STARTING_PARENT
                || item.eventTransactionOrThrow().transactionGroupRole() == TransactionGroupRole.PARENT;
    }

    /**
     * Determines the Hedera functionality of the transaction in the given block item.
     *
     * @param item the block item containing the event transaction
     * @param currentIndex the index of the block item in the block
     * @return the Hedera functionality of the transaction, or HederaFunctionality.NONE if it cannot be determined
     */
    private HederaFunctionality functionOfTxn(final BlockItem item, final int currentIndex) {
        try {
            final var eventTransaction = item.eventTransactionOrThrow();
            if (eventTransaction.hasApplicationTransaction()) {
                final TransactionBody transactionBody = bodyFrom(eventTransaction);
                return HapiUtils.functionOf(transactionBody);
            }
        } catch (Exception e) {
            Assertions.fail("Failed to parse event transaction at index " + currentIndex + ": " + e.getMessage());
            return HederaFunctionality.NONE;
        }
        return HederaFunctionality.NONE;
    }

    @NonNull
    public static TransactionBody bodyFrom(final EventTransaction eventTransaction) throws ParseException {
        final var applicationTransaction = eventTransaction.applicationTransactionOrThrow();
        final var txn = Transaction.PROTOBUF.parse(Bytes.wrap(applicationTransaction.toByteArray()));
        final TransactionBody transactionBody;
        if (txn.signedTransactionBytes() != null && txn.signedTransactionBytes().length() > 0) {
            transactionBody = TransactionBody.PROTOBUF.parse(SignedTransaction.PROTOBUF
                    .parse(txn.signedTransactionBytes())
                    .bodyBytes());
        } else {
            transactionBody = TransactionBody.PROTOBUF.parse(txn.bodyBytes());
        }
        return transactionBody;
    }

    private static final Set<BlockItem.ItemOneOfType> OPTIONAL_ITEM_TYPES = Set.of(TRANSACTION_OUTPUT, TRACE_DATA);

    /**
     * Validates a transaction group (transaction + result + optional outputs).
     * Returns the index of the next item after this group.
     */
    private static int validateTransactionGroup(final List<BlockItem> items, int transactionIndex) {
        if (transactionIndex + 1 >= items.size()) {
            Assertions.fail("Event transaction at end of block with no result");
        }

        // Check for transaction result
        BlockItem nextItem = items.get(transactionIndex + 1);
        if (!nextItem.hasTransactionResult()) {
            logger.error("Expected transaction result at index {}, found: {}", transactionIndex + 1, nextItem);
            Assertions.fail("Event transaction must be followed by transaction result");
        }

        // Check for optional transaction outputs
        int currentIndex = transactionIndex + 2;
        while (currentIndex < items.size()
                && OPTIONAL_ITEM_TYPES.contains(items.get(currentIndex).item().kind())) {
            final var item = items.get(currentIndex);
            switch (item.item().kind()) {
                case TRANSACTION_OUTPUT -> {
                    if (TransactionOutput.DEFAULT.equals(item.transactionOutputOrThrow())) {
                        Assertions.fail("Transaction output at index " + currentIndex
                                + " is equal to TransactionOutput.DEFAULT");
                    }
                }
                case TRACE_DATA -> {
                    if (TraceData.DEFAULT.equals(item.traceDataOrThrow())) {
                        Assertions.fail("Found default trace data at index " + currentIndex);
                    }
                }
                default ->
                    throw new IllegalStateException("Should be unreachable, but got "
                            + items.get(currentIndex).item());
            }
            currentIndex++;
        }

        return currentIndex;
    }

    /**
     * Validates a result-only group (result without preceding event transaction).
     * This is typically used for transaction outputs for atomic batch transactions.
     *
     * @param items the list of block items
     * @param resultIndex the index of the transaction result item
     * @return the index of the next item after this group
     */
    private static int validateResultOnlyGroup(final List<BlockItem> items, int resultIndex) {
        if (!items.get(resultIndex).hasTransactionResult()) {
            Assertions.fail(
                    "Expected transaction result at index " + resultIndex + ", found " + items.get(resultIndex));
        }
        int currentIndex = resultIndex + 1;
        while (currentIndex < items.size()
                && OPTIONAL_ITEM_TYPES.contains(items.get(currentIndex).item().kind())) {
            final var item = items.get(currentIndex);
            switch (item.item().kind()) {
                case TRANSACTION_OUTPUT -> {
                    if (TransactionOutput.DEFAULT.equals(item.transactionOutputOrThrow())) {
                        Assertions.fail("Transaction output at index " + currentIndex
                                + " is equal to TransactionOutput.DEFAULT");
                    }
                }
                case TRACE_DATA -> {
                    if (TraceData.DEFAULT.equals(item.traceDataOrThrow())) {
                        Assertions.fail("Found default trace data at index " + currentIndex);
                    }
                }
                default ->
                    throw new IllegalStateException("Should be unreachable, but got "
                            + items.get(currentIndex).item());
            }
            currentIndex++;
        }
        return currentIndex;
    }
}
