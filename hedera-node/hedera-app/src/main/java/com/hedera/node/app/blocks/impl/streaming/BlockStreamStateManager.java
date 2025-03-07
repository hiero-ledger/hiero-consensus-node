// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A class to manage the Block Stream state. It will keep track of the current block's state as well as
 * keep in-memory states for previous blocks until a BlockAcknowledgement is received for that particular block.
 */
public class BlockStreamStateManager {
    private final Map<Long, BlockState> blockStates = new ConcurrentHashMap<>();
    private volatile BlockState currentBlockState;
    private final Object statesLock = new Object();

    /**
     * Default constructor.
     */
    public BlockStreamStateManager() {}

    /**
     * Register the current block that is processing.
     *
     * @param blockNumber the block number to register
     */
    public void registerBlock(long blockNumber) {
        currentBlockState = BlockState.from(blockNumber);
        blockStates.put(blockNumber, currentBlockState);
    }

    /**
     * @return the block state for the current processing block
     */
    public BlockState getCurrentBlockState() {
        return currentBlockState;
    }

    /**
     * @param blockNumber the block number
     * @return the block state for the given block number
     */
    public BlockState getBlockState(long blockNumber) {
        return blockStates.get(blockNumber);
    }

    /**
     * Nullify the current block state.
     */
    public void nullifyCurrentBlockState() {
        synchronized (statesLock) {
            currentBlockState = null;
        }
    }

    /**
     * @param blockNumber the block number for which to clean up the state
     */
    public void cleanUpBlockState(long blockNumber) {
        synchronized (statesLock) {
            final BlockState removedState = blockStates.remove(blockNumber);
            if (removedState != null && currentBlockState != null && currentBlockState.blockNumber() == blockNumber) {
                currentBlockState = null;
            }
        }
    }
}
