// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.hapi.block.stream.BlockItem;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.BlockItemSet;
import org.hiero.block.api.PublishStreamRequest;

/**
 * Represents the state of a block being streamed to block nodes. This class maintains the block items,
 * completion status, and request generation for a specific block number.
 * The block state goes through the following lifecycle:
 * <ul>
 *     <li>Created when a new block is opened</li>
 *     <li>Block items are added sequentially</li>
 *     <li>Requests are generated from accumulated items capped by a configurable batch size</li>
 *     <li>Block is marked as complete after all items including BlockProof are added</li>
 * </ul>
 */
public class BlockState {
    private static final Logger logger = LogManager.getLogger(BlockState.class);
    private final long blockNumber;
    private final List<BlockItem> items = new ArrayList<>();
    private final List<PublishStreamRequest> requests = new ArrayList<>();
    private final AtomicBoolean requestsCreated = new AtomicBoolean(false);
    private Instant closedTimestamp = null;

    /**
     * Create a new block state for the specified block number.
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
     * Add an item to the BlockState, this will not create a PublishStreamRequest.
     * @param item the item to add
     */
    public void addItem(final BlockItem item) {
        items.add(item);
    }

    /**
     * Get the size of the list of PublishStreamRequests
     *
     * @return the size of the list of PublishStreamRequests
     */
    public int requestsSize() {
        return requests.size();
    }

    /**
     * Gets a previously generated publish stream request at the specified index.
     *
     * @param index the index of the request to retrieve
     * @return the request at the given index
     */
    public @Nullable PublishStreamRequest getRequest(final int index) {
        if (index < 0 || requests.isEmpty()) {
            return null;
        }

        return requests.get(index);
    }

    /**
     * Check if the block is complete. A block is considered complete
     * when all its items including the block proof have been processed into requests.
     *
     * @return true if the block is complete, false otherwise
     */
    public boolean requestsCompleted() {
        return requestsCreated.get();
    }

    /**
     * Indicates the BlockState has been populated with all PublishStreamRequests.
     * This should be called after the block proof has been added and all items processed.
     */
    public void setRequestsCompleted() {
        this.requestsCreated.set(true);
    }

    /**
     * Sets the completion time of the block to now.
     */
    public void setCompletionTimestamp() {
        this.closedTimestamp = Instant.now();
    }

    /**
     * Get the completion time of the block.
     *
     * @return the completion time, or null if the block is not complete
     */
    public Instant completionTimestamp() {
        return closedTimestamp;
    }

    /**
     * Create a new PublishStreamRequest from the current items in the BlockState.
     * The request will include up to batchSize items or fewer if a block proof is included.
     *
     * @param batchSize the size of the batch to create
     * @param forceCreation if true, create a request even if the batch size is not met
     */
    public void createRequestFromCurrentItems(final int batchSize, final boolean forceCreation) {
        final int maxItems = Math.max(1, batchSize); // if batchSize is less than 1, set the size to 1
        final List<BlockItem> blockItems = new ArrayList<>(maxItems);

        if (items.isEmpty()) {
            return;
        }

        if (items.size() >= maxItems || forceCreation) {
            final Iterator<BlockItem> it = items.iterator();
            while (it.hasNext() && blockItems.size() != maxItems) {
                blockItems.add(it.next());
                it.remove();
            }
        }

        if (blockItems.isEmpty()) {
            return;
        }

        // Create BlockItemSet by adding all items at once
        final BlockItemSet itemSet =
                BlockItemSet.newBuilder().blockItems(blockItems).build();

        // Create the request and add it to the list
        final PublishStreamRequest request =
                PublishStreamRequest.newBuilder().blockItems(itemSet).build();

        logger.debug("Added request to block {} - request count now: {}", blockNumber, requests.size());
        requests.add(request);

        if (!items.isEmpty()) {
            createRequestFromCurrentItems(batchSize, forceCreation);
        }
    }

    @Override
    public String toString() {
        return "BlockState{" + "blockNumber="
                + blockNumber + ", items="
                + items + ", requests="
                + requests + ", requestsCreated="
                + requestsCreated + ", closedTimestamp="
                + closedTimestamp + '}';
    }
}
