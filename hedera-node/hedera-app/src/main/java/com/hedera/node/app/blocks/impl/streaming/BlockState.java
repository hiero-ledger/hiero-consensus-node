// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.hapi.block.BlockItemSet;
import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.stream.BlockItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Track the current block state
 */
public class BlockState {
    private static final Logger logger = LogManager.getLogger(BlockState.class);
    private final long blockNumber;
    private final List<BlockItem> items = new ArrayList<>();
    private final List<PublishStreamRequest> requests = new ArrayList<>();
    private final AtomicBoolean requestsCreated = new AtomicBoolean(false);
    private Instant closedTimestamp = null;

    /**
     * Create a new block state for a block number
     *
     * @param blockNumber the block number
     */
    public BlockState(final long blockNumber) {
        this.blockNumber = blockNumber;
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
    public boolean requestsCompleted() {
        return requestsCreated.get();
    }

    /**
     * Indicates the BlockState has been populated with all PublishStreamRequests
     */
    public void setRequestsCompleted() {
        this.requestsCreated.set(true);
    }

    public void setCompletionTimestamp() {
        this.closedTimestamp = Instant.now();
    }

    /**
     * Get the completion time of the block
     *
     * @return the completion time, or null if the block is not complete
     */
    public Instant completionTimestamp() {
        return closedTimestamp;
    }

    @Override
    public String toString() {
        return "BlockState{" + "blockNumber="
                + blockNumber + ", items="
                + items + ", requests="
                + requests + ", isComplete="
                + requestsCreated + ", completionTimestamp="
                + closedTimestamp + '}';
    }

    public void createRequestFromCurrentItems(int batchSize, boolean forceCreation) {
        batchSize = Math.max(1, batchSize); // if batchSize is less than 1, set the size to 1
        final List<BlockItem> blockItems = new ArrayList<>(batchSize);

        if (items.size() >= batchSize || (forceCreation && !items.isEmpty())) {
            final Iterator<BlockItem> it = items.iterator();
            while (it.hasNext() && blockItems.size() != batchSize) {
                blockItems.add(it.next());
                it.remove();
            }
        } else {
            return;
        }

        // Create BlockItemSet by adding all items at once
        final BlockItemSet itemSet =
                BlockItemSet.newBuilder().blockItems(blockItems).build();

        // Create the request and add it to the list
        final PublishStreamRequest request =
                PublishStreamRequest.newBuilder().blockItems(itemSet).build();

        logger.debug(
                "[{}] Added request to block {} - request count now: {}",
                Thread.currentThread().getName(),
                blockNumber,
                requests.size());
        requests.add(request);
    }
}
