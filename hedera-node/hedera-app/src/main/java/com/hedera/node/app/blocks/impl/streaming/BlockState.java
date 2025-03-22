// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.hapi.block.stream.BlockItem;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Track the current block state
 *
 * @param blockNumber the block number of this block state
 * @param items the list of items in this block state
 */
public record BlockState(long blockNumber, List<BlockItem> items) {

    /**
     * Create a new block state for a block number
     *
     * @param blockNumber the block number
     * @return the block state for the specified block number
     */
    public static @NonNull BlockState from(long blockNumber) {
        return new BlockState(blockNumber, new ArrayList<>());
    }
}
