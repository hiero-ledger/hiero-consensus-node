// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Track the current block state
 */
public class BlockState {
    private final long blockNumber;
    private final List<Bytes> itemBytes;
    private final List<PublishStreamRequest> requests;
    private boolean isComplete;

    /**
     * Create a new block state for a block number
     *
     * @param blockNumber the block number
     */
    public BlockState(long blockNumber) {
        this.blockNumber = blockNumber;
        this.itemBytes = new ArrayList<>();
        this.requests = new ArrayList<>();
        this.isComplete = false;
    }

    /**
     * Create a new block state for a block number
     *
     * @param blockNumber the block number
     * @return the block state for the specified block number
     */
    public static @NonNull BlockState from(long blockNumber) {
        return new BlockState(blockNumber);
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
    public List<Bytes> itemBytes() {
        return itemBytes;
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
        return isComplete;
    }

    /**
     * Set the block as complete
     */
    public void setComplete() {
        this.isComplete = true;
    }
}
