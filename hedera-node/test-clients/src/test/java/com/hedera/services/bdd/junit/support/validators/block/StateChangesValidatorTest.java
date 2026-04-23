// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.services.bdd.junit.support.validators.block.StateChangesValidator.currentBlockHashFromNextBlockFooter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.output.BlockFooter;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

class StateChangesValidatorTest {

    private static final Bytes PREV_BLOCK_ROOT_HASH = Bytes.wrap(new byte[] {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
        33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48
    });

    @Test
    void readsPreviousBlockRootHashFromCompleteNextBlock() {
        final var nextBlock = blockBuilder()
                .items(List.of(headerItem(100L), footerItem(PREV_BLOCK_ROOT_HASH), proofItem(100L)))
                .build();

        assertEquals(PREV_BLOCK_ROOT_HASH, currentBlockHashFromNextBlockFooter(nextBlock));
    }

    @Test
    void readsPreviousBlockRootHashFromIncompleteNextBlock() {
        // Regression for #24709: an incomplete block flushed at a freeze round ends with the
        // BlockFooter as its last item (no BlockProof). The validator must still recover the
        // previous block's root hash from the footer; otherwise the incremental block-hashes
        // chain desynchronizes from the signer's and later proofs fail to verify.
        final var nextBlock = blockBuilder()
                .items(List.of(headerItem(100L), footerItem(PREV_BLOCK_ROOT_HASH)))
                .build();

        assertEquals(PREV_BLOCK_ROOT_HASH, currentBlockHashFromNextBlockFooter(nextBlock));
    }

    @Test
    void returnsNullForEmptyNextBlock() {
        final var nextBlock = blockBuilder().items(List.of()).build();
        assertNull(currentBlockHashFromNextBlockFooter(nextBlock));
    }

    @Test
    void returnsNullWhenNextBlockHasNoFooter() {
        final var nextBlock = blockBuilder().items(List.of(proofItem(100L))).build();

        assertNull(currentBlockHashFromNextBlockFooter(nextBlock));
    }

    /**
     * Equivalence proof: the helper should treat complete and incomplete next-blocks identically
     * when their footers carry the same data. Before the fix, only the complete shape was
     * recognized; the incomplete shape silently left the validator's previousBlockHash stale,
     * desyncing the incremental block-hashes chain and producing spurious "Invalid signature in
     * proof" failures in subsequent sampled proofs.
     */
    @Test
    void completeAndIncompleteNextBlocksYieldSameHash() {
        final var completeNextBlock = blockBuilder()
                .items(List.of(headerItem(100L), footerItem(PREV_BLOCK_ROOT_HASH), proofItem(100L)))
                .build();
        final var incompleteNextBlock = blockBuilder()
                .items(List.of(headerItem(100L), footerItem(PREV_BLOCK_ROOT_HASH)))
                .build();

        final var fromComplete = currentBlockHashFromNextBlockFooter(completeNextBlock);
        final var fromIncomplete = currentBlockHashFromNextBlockFooter(incompleteNextBlock);

        assertEquals(PREV_BLOCK_ROOT_HASH, fromComplete);
        assertEquals(fromComplete, fromIncomplete);
    }

    /**
     * Demonstrates the bug: the pre-fix shortcut assumed the footer always sits at
     * {@code items.get(size - 2)}. For an incomplete block that assumption is wrong — {@code
     * size - 2} is the header (or some other non-footer item), so the old {@code hasBlockFooter()}
     * guard returned false and {@code previousBlockHash} was never updated. The helper now reads
     * the last item first, handling both shapes correctly.
     */
    @Test
    void oldSizeMinusTwoShortcutWouldHaveMissedIncompleteFooter() {
        final var incompleteNextBlock = blockBuilder()
                .items(List.of(headerItem(100L), footerItem(PREV_BLOCK_ROOT_HASH)))
                .build();
        final var items = incompleteNextBlock.items();

        // Inline the old buggy logic to prove it would have failed for this shape:
        final int oldFooterIndex = items.size() - 2;
        assertFalse(
                items.get(oldFooterIndex).hasBlockFooter(),
                "The old size-2 lookup finds a non-footer item in an incomplete block — this is the bug");

        // The new helper recovers the hash correctly:
        assertEquals(PREV_BLOCK_ROOT_HASH, currentBlockHashFromNextBlockFooter(incompleteNextBlock));
    }

    private static Block.Builder blockBuilder() {
        return Block.newBuilder();
    }

    private static BlockItem headerItem(final long blockNumber) {
        return BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().number(blockNumber).build())
                .build();
    }

    private static BlockItem footerItem(final Bytes previousBlockRootHash) {
        return BlockItem.newBuilder()
                .blockFooter(BlockFooter.newBuilder()
                        .previousBlockRootHash(previousBlockRootHash)
                        .build())
                .build();
    }

    private static BlockItem proofItem(final long blockNumber) {
        return BlockItem.newBuilder()
                .blockProof(BlockProof.newBuilder().block(blockNumber).build())
                .build();
    }
}
