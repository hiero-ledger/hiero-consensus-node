// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
import static com.hedera.services.bdd.spec.HapiPropertySource.NODE_BLOCK_STREAM_DIR;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.services.bdd.junit.support.BlockStreamAccess;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Paths;
import java.util.List;
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
        final var blocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(
                node0Dir.resolve("data/block-streams/" + NODE_BLOCK_STREAM_DIR));
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

    private static void validate(Block block, final int blocksRemaining) {
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

    private static void validateRounds(final List<BlockItem> roundItems) {
        int currentIndex = 0;
        while (currentIndex < roundItems.size()) {
            currentIndex = validateSingleRound(roundItems, currentIndex);
        }
    }

    /**
     * Validates a single round within a block, starting at the given index.
     * Returns the index of the next item after this round.
     */
    private static int validateSingleRound(final List<BlockItem> items, int startIndex) {
        // Validate round header
        if (!items.get(startIndex).hasRoundHeader()) {
            logger.error("Expected round header at index {}, found: {}", startIndex, items.get(startIndex));
            Assertions.fail("Round must start with a round header");
        }

        int currentIndex = startIndex + 1;
        boolean hasEventOrStateChange = false;

        // Process items in this round until we hit the next round header or end of items
        while (currentIndex < items.size() && !items.get(currentIndex).hasRoundHeader()) {
            BlockItem item = items.get(currentIndex);

            if (item.hasEventHeader() || item.hasStateChanges()) {
                hasEventOrStateChange = true;
                currentIndex++;
            } else if (item.hasEventTransaction()) {
                currentIndex = validateTransactionGroup(items, currentIndex);
            } else if (item.hasTransactionResult() || item.hasTransactionOutput()) {
                logger.error(
                        "Found transaction result or output without preceding transaction at index {}", currentIndex);
                Assertions.fail(
                        "Found transaction result or output without preceding transaction at index " + currentIndex);
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
        while (currentIndex < items.size() && items.get(currentIndex).hasTransactionOutput()) {
            // Check that transaction output is not equal to TransactionOutput.DEFAULT
            if (TransactionOutput.DEFAULT.equals(items.get(currentIndex).transactionOutput())) {
                logger.error("Transaction output at index {} is equal to TransactionOutput.DEFAULT", currentIndex);
                Assertions.fail(
                        "Transaction output at index " + currentIndex + " is equal to TransactionOutput.DEFAULT");
            }
            currentIndex++;
        }

        return currentIndex;
    }
}
