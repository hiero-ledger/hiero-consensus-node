// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
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
 * Validates the structure of blocks, including both normal blocks and Wrapped Record Blocks (WRBs)
 * produced by the historical record file wrapping process.
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

    /**
     * Returns {@code true} if the given block items represent a Wrapped Record Block.
     * Detection is based on the presence of a {@code RECORD_FILE} item.
     */
    public static boolean isWrappedRecordBlock(@NonNull final List<BlockItem> items) {
        for (final var item : items) {
            if (item.hasRecordFile()) {
                return true;
            }
        }
        return false;
    }

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

        validateBlockHeader(items.getFirst());

        if (isWrappedRecordBlock(items)) {
            validateWrappedRecordBlock(items, blocksRemaining);
        } else {
            validateNormalBlock(items, blocksRemaining);
        }
    }

    private void validateNormalBlock(@NonNull final List<BlockItem> items, final int blocksRemaining) {
        validateRounds(items.subList(1, items.size() - 1));

        if (blocksRemaining > REASONABLE_NUM_PENDING_PROOFS_AT_FREEZE
                && items.getLast().hasBlockProof()) {
            validateBlockProof(items.getLast());
        }
    }

    private void validateWrappedRecordBlock(@NonNull final List<BlockItem> items, final int blocksRemaining) {
        boolean foundRecordFile = false;
        boolean foundFooter = false;
        boolean foundProof = false;

        for (int i = 1; i < items.size(); i++) {
            final var item = items.get(i);
            final var kind = item.item().kind();
            switch (kind) {
                case STATE_CHANGES -> {
                    if (foundRecordFile) {
                        Assertions.fail("WRB StateChanges found after RecordFileItem at index " + i);
                    }
                    if (foundFooter) {
                        Assertions.fail("WRB StateChanges found after BlockFooter at index " + i);
                    }
                }
                case RECORD_FILE -> {
                    if (foundRecordFile) {
                        Assertions.fail("WRB contains more than one RecordFileItem at index " + i);
                    }
                    if (foundFooter) {
                        Assertions.fail("WRB RecordFileItem found after BlockFooter at index " + i);
                    }
                    validateRecordFileItem(item, i);
                    foundRecordFile = true;
                }
                case BLOCK_FOOTER -> {
                    if (foundFooter) {
                        Assertions.fail("WRB contains duplicate BlockFooter at index " + i);
                    }
                    if (!foundRecordFile) {
                        Assertions.fail("WRB BlockFooter found before RecordFileItem at index " + i);
                    }
                    foundFooter = true;
                }
                case BLOCK_PROOF -> {
                    if (!foundFooter) {
                        Assertions.fail("WRB BlockProof found before BlockFooter at index " + i);
                    }
                    validateWrappedBlockProof(item, i);
                    foundProof = true;
                }
                default -> Assertions.fail("WRB contains unexpected item type " + kind + " at index " + i);
            }
        }

        if (!foundRecordFile) {
            Assertions.fail("WRB is missing RecordFileItem");
        }
        if (!foundFooter) {
            Assertions.fail("WRB is missing BlockFooter");
        }
        if (!foundProof && blocksRemaining > REASONABLE_NUM_PENDING_PROOFS_AT_FREEZE) {
            Assertions.fail("WRB is missing BlockProof");
        }
    }

    private static void validateRecordFileItem(@NonNull final BlockItem item, final int index) {
        final var recordFile = item.recordFileOrThrow();
        if (!recordFile.hasCreationTime()) {
            Assertions.fail("WRB RecordFileItem at index " + index + " is missing creation_time");
        }
        if (!recordFile.hasRecordFileContents()) {
            Assertions.fail("WRB RecordFileItem at index " + index + " is missing record_file_contents");
        }
    }

    private static void validateWrappedBlockProof(@NonNull final BlockItem item, final int index) {
        final var proof = item.blockProofOrThrow();
        if (!proof.hasSignedRecordFileProof()) {
            Assertions.fail("WRB BlockProof at index " + index + " must use SignedRecordFileProof, found: "
                    + proof.proof().kind());
        }
        final var signedProof = proof.signedRecordFileProofOrThrow();
        final int version = signedProof.version();
        if (version != 2 && version != 5 && version != 6) {
            Assertions.fail("WRB SignedRecordFileProof at index " + index + " has invalid version " + version
                    + " (expected 2, 5, or 6)");
        }
        if (signedProof.recordFileSignatures().isEmpty()) {
            Assertions.fail("WRB SignedRecordFileProof at index " + index + " has no signatures");
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

    private int validateSingleRound(final List<BlockItem> items, int startIndex) {
        if (!items.get(startIndex).hasRoundHeader()) {
            logger.error("Expected round header at index {}, found: {}", startIndex, items.get(startIndex));
            Assertions.fail("Round must start with a round header");
        }
        int currentIndex = startIndex + 1;
        boolean insideEvent = false;
        boolean hasEventOrStateChange = false;
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
