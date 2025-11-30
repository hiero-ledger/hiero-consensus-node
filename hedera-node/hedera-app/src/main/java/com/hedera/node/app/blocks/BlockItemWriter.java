// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.internal.network.PendingProof;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Writes serialized block items to a destination stream.
 */
public interface BlockItemWriter {
    /**
     * Opens a block for writing.
     *
     * @param blockNumber the number of the block to open
     */
    void openBlock(final long blockNumber);

    /**
     * Writes a PBJ item to the destination stream.
     * @param item the item to write
     */
    void writePbjItem(@NonNull final BlockItem item);

    /**
     * Closes a block that is complete with a proof.
     */
    void closeCompleteBlock();

    /**
     * Flushes to disk a block that is still waiting for a complete proof.
     * @param pendingProof the proof pending a signature
     */
    void flushPendingBlock(@NonNull PendingProof pendingProof);
}
