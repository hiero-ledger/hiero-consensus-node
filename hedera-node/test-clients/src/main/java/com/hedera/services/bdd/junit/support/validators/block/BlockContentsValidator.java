// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
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
        final var blocks =
                BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(node0Dir.resolve("data/blockStreams/block-11.12.3"));
        validator.validateBlocks(blocks);
    }

    public static final Factory FACTORY = spec -> new BlockContentsValidator();

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
        // Process items in this round until we hit the next round header or end of items
        while (currentIndex < items.size() && !items.get(currentIndex).hasRoundHeader()) {
            final var item = items.get(currentIndex);
            final var kind = item.item().kind();
            switch (kind) {
                case EVENT_HEADER, STATE_CHANGES -> hasEventOrStateChange = true;
                case RECORD_FILE, FILTERED_ITEM_HASH ->
                    Assertions.fail("Unexpected item type " + kind + " at index " + currentIndex);
                default -> {
                    // No-op
                }
            }
            currentIndex++;
        }
        if (!hasEventOrStateChange) {
            logger.error("Round starting at index {} has no event headers or state changes", startIndex);
            Assertions.fail("Round starting at index " + startIndex + " has no event headers or state changes");
        }
        return currentIndex;
    }
}
