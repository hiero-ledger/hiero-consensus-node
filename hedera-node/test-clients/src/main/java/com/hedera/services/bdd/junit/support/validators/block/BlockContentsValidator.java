// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
        final var node0Dir = Paths.get(
                        "/Users/derektriley/git/workspace-1/hiero-consensus-node/hedera-node/test-clients/build/hapi-test/node0/data/blockStreams/block-11.12.3")
                .toAbsolutePath()
                .normalize();
        final var node2Dir = Paths.get(
                        "/Users/derektriley/git/workspace-1/hiero-consensus-node/hedera-node/test-clients/build/hapi-test/node2/data/blockStreams/block-11.12.5")
                .toAbsolutePath()
                .normalize();
        final var node0Blocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(node0Dir);
        final var node2Blocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(node2Dir);
        printFirstDifference(node0Blocks, node2Blocks);
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

        // A block SHALL end with a `block_proof`; skip check for blocks near the end
        // (pending async proofs at freeze) or incomplete blocks in the middle (can happen
        // when nodes restart and all nodes wrote the block before the async proof arrived)
        if (blocksRemaining > REASONABLE_NUM_PENDING_PROOFS_AT_FREEZE
                && items.getLast().hasBlockProof()) {
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
        boolean insideEvent = false;
        boolean hasEventOrStateChange = false;
        // Process items in this round until we hit the next round header or end of items
        while (currentIndex < items.size() && !items.get(currentIndex).hasRoundHeader()) {
            final var item = items.get(currentIndex);
            final var kind = item.item().kind();
            switch (kind) {
                case EVENT_HEADER -> hasEventOrStateChange = insideEvent = true;
                case BLOCK_FOOTER -> insideEvent = false;
                case STATE_CHANGES -> hasEventOrStateChange = true;
                case SIGNED_TRANSACTION ->
                    assertTrue(insideEvent, "Signed transaction found outside of event at index " + currentIndex);
                case RECORD_FILE, FILTERED_SINGLE_ITEM ->
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

    private static void printFirstDifference(final List<Block> node0Blocks, final List<Block> node2Blocks) {
        final var commonBlockCount = Math.min(node0Blocks.size(), node2Blocks.size());
        for (int blockIndex = 0; blockIndex < commonBlockCount; blockIndex++) {
            final var node0Block = node0Blocks.get(blockIndex);
            final var node2Block = node2Blocks.get(blockIndex);
            final var node0Items = node0Block.items();
            final var node2Items = node2Block.items();
            final var commonItemCount = Math.min(node0Items.size(), node2Items.size());
            for (int itemIndex = 0; itemIndex < commonItemCount; itemIndex++) {
                final var node0Item = node0Items.get(itemIndex);
                final var node2Item = node2Items.get(itemIndex);
                if (!node0Item.equals(node2Item)) {
                    System.out.printf(
                            """
                            First differing block item found:
                              blockIndex=%d
                              node0BlockNumber=%d
                              node2BlockNumber=%d
                              itemIndex=%d
                              node0ItemKind=%s
                              node2ItemKind=%s
                              node0Item=%s
                              node2Item=%s
                            """,
                            blockIndex,
                            blockNumberOf(node0Block),
                            blockNumberOf(node2Block),
                            itemIndex,
                            node0Item.item().kind(),
                            node2Item.item().kind(),
                            node0Item,
                            node2Item);
                    return;
                }
            }
            if (node0Items.size() != node2Items.size()) {
                System.out.printf(
                        """
                        First differing block found due to item count mismatch:
                          blockIndex=%d
                          node0BlockNumber=%d
                          node2BlockNumber=%d
                          node0ItemCount=%d
                          node2ItemCount=%d
                        """,
                        blockIndex,
                        blockNumberOf(node0Block),
                        blockNumberOf(node2Block),
                        node0Items.size(),
                        node2Items.size());
                return;
            }
        }
        if (node0Blocks.size() != node2Blocks.size()) {
            System.out.printf(
                    """
                    Block stream sizes differ:
                      node0BlockCount=%d
                      node2BlockCount=%d
                    """,
                    node0Blocks.size(),
                    node2Blocks.size());
            return;
        }
        System.out.println("No differing block item found between node0Dir and node2Dir.");
    }

    private static long blockNumberOf(final Block block) {
        final var items = block.items();
        return !items.isEmpty() && items.getFirst().hasBlockHeader()
                ? items.getFirst().blockHeaderOrThrow().number()
                : -1;
    }
}
