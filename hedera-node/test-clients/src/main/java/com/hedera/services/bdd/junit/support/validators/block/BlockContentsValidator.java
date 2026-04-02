// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.junit.support.StreamFileAccess;
import com.hedera.services.stream.proto.RecordStreamFile;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
        final var recordStreamsDir = "/Users/derektriley/Downloads/hapi-test 7/node0/data/recordStreams/record11.12.3";
        final Set<Long> targetBlocks = Set.of(294L);

        try {
            final var streamData = StreamFileAccess.STREAM_FILE_ACCESS.readStreamDataFrom(recordStreamsDir, "sidecar");
            final var matchingFiles = streamData.files().stream()
                    .filter(file -> targetBlocks.contains(file.getBlockNumber()))
                    .toList();

            for (final var file : matchingFiles) {
                System.out.printf("%n=== Block %d ===%n%s%n", file.getBlockNumber(), file);
            }

            final var foundBlocks =
                    matchingFiles.stream().map(RecordStreamFile::getBlockNumber).collect(Collectors.toSet());
            targetBlocks.stream()
                    .filter(blockNo -> !foundBlocks.contains(blockNo))
                    .sorted()
                    .forEach(blockNo -> System.out.printf("No record stream file found for block %d%n", blockNo));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read record stream files from " + recordStreamsDir, e);
        }
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
}
