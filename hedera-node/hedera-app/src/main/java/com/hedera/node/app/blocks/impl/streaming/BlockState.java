// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.stream.BlockItem;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Track the current block state
 */
public class BlockState {
    private final long blockNumber;
    private final List<BlockItem> items;
    private final List<PublishStreamRequest> requests;
    private final AtomicBoolean isComplete = new AtomicBoolean(false);
    private Instant completionTime = null;

    /**
     * Create a new block state for a block number
     *
     * @param blockNumber the block number
     */
    public BlockState(long blockNumber, @NonNull List<BlockItem> items) {
        this.blockNumber = blockNumber;
        this.items = items;
        this.requests = new ArrayList<>();
    }

    /**
     * Get the block number
     *
     * @return the block number
     */
    public long blockNumber() {
        return blockNumber;
    }

    /**
     * Get the list of item bytes
     *
     * @return the list of item bytes
     */
    public List<BlockItem> items() {
        return items;
    }

    /**
     * Get the list of publish stream requests
     *
     * @return the list of publish stream requests
     */
    public List<PublishStreamRequest> requests() {
        return requests;
    }

    /**
     * Check if the block is complete
     *
     * @return true if the block is complete, false otherwise
     */
    public boolean isComplete() {
        return isComplete.get();
    }

    /**
     * Set the block as complete
     */
    public void setComplete() {
        this.completionTime = Instant.now();
        this.isComplete.set(true);
    }

    /**
     * Get the completion time of the block
     *
     * @return the completion time, or null if the block is not complete
     */
    public Instant getCompletionTime() {
        return completionTime;
    }
}
