// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.hapi.block.stream.BlockItem;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    enum ItemState {
        NIL,
        ADDED,
        PACKED,
        SENT
    }

    record RequestWrapper(int index, PublishStreamRequest request, AtomicBoolean isSent) {}

    record ItemInfo(AtomicReference<ItemState> state, AtomicInteger requestIndex) {
        ItemInfo() {
            this(new AtomicReference<>(ItemState.NIL), new AtomicInteger(-1));
        }

        void packedInRequest(final int requestIdx) {
            state.set(ItemState.PACKED);
            requestIndex.set(requestIdx);
        }
    }

    private final long blockNumber;
    private final Queue<BlockItem> pendingItems = new ConcurrentLinkedQueue<>();
    private final ConcurrentMap<Integer, RequestWrapper> requestsByIndex = new ConcurrentHashMap<>();
    private final AtomicInteger requestIdxCtr = new AtomicInteger(0);
    private final AtomicReference<Instant> closedTimestamp = new AtomicReference<>();

    private final ItemInfo headerItemInfo = new ItemInfo();
    private final ItemInfo proofItemInfo = new ItemInfo();
    private final ItemInfo preProofItemInfo = new ItemInfo();

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
        if (item == null) {
            return;
        }

        if (closedTimestamp.get() != null) {
            throw new IllegalStateException("Block is closed; adding more items is not permitted");
        }

        if (item.hasBlockHeader() && !headerItemInfo.state.compareAndSet(ItemState.NIL, ItemState.ADDED)) {
            logger.warn(
                    "[Block {}] Block header item added, but block header already encountered (state={})",
                    blockNumber,
                    headerItemInfo.state.get());
        } else if (item.hasBlockProof() && !proofItemInfo.state.compareAndSet(ItemState.NIL, ItemState.ADDED)) {
            logger.warn(
                    "[Block {}] Block proof item added, but block proof already encountered (state={})",
                    blockNumber,
                    proofItemInfo.state.get());
        } else if (item.hasStateChanges() && !preProofItemInfo.state.compareAndSet(ItemState.NIL, ItemState.ADDED)) {
            logger.warn(
                    "[Block {}] Block state changes item added, but state changes already encountered (state={})",
                    blockNumber,
                    preProofItemInfo.state.get());
        }

        pendingItems.add(item);
    }

    /**
     * Get the number of requests that have been created for this block up to the time this method is invoked.
     * Additional requests may still be created (e.g. if more items are added to the block.)
     *
     * @return the number of requests that have been created for this block
     */
    public int numRequestsCreated() {
        return requestsByIndex.size();
    }

    /**
     * Gets a previously generated publish stream request at the specified index.
     *
     * @param index the index of the request to retrieve
     * @return the request at the given index
     */
    public @Nullable PublishStreamRequest getRequest(final int index) {
        final RequestWrapper rs = requestsByIndex.get(index);
        return rs == null ? null : rs.request;
    }

    /**
     * Mark this block as closed. No additional items can be added to this block after it is closed.
     */
    public void closeBlock() {
        final Instant now = Instant.now();
        closedTimestamp.set(now);
        logger.debug("[Block {}] closed at {}", blockNumber, now);
    }

    /**
     * Get the completion time of the block.
     *
     * @return the completion time, or null if the block is not complete
     */
    public @Nullable Instant closedTimestamp() {
        return closedTimestamp.get();
    }

    /**
     * Processes any pending items associated with this block and assigns them to one or more requests that can be sent
     * to a block node.
     *
     * @param batchSize the maximum number of items to include in the request; if this value is less than 1 then the
     *                  batch size is set to 1
     */
    public void processPendingItems(final int batchSize) {
        if (pendingItems.isEmpty()) {
            return; // nothing to do
        }

        final int maxItems = Math.max(1, batchSize); // if batch size is less than 1, set the size to 1

        /*
         * There are four scenarios in which we want to create a new request:
         * 1. The number of items equals the batch size
         * 2. The new request would include the block header, regardless if it matches the batch size
         * 3. The new request would include any pending items before the block proof is created (block proof could take
         *    longer to process)
         * 4. The new request contains the block proof
         */

        final boolean hasEnoughItemsForBatch = pendingItems.size() >= maxItems;
        final boolean headerNeedsToBeSent = ItemState.ADDED == headerItemInfo.state.get();
        final boolean proofNeedsToBeSent = ItemState.ADDED == proofItemInfo.state.get();
        final boolean preProofNeedsToBeSent = ItemState.ADDED == preProofItemInfo.state.get();

        if (!hasEnoughItemsForBatch && !headerNeedsToBeSent && !proofNeedsToBeSent && !preProofNeedsToBeSent) {
            return; // nothing ready to be sent
        }

        final List<BlockItem> blockItems = new ArrayList<>(maxItems);
        final int index = requestIdxCtr.getAndIncrement();
        final Iterator<BlockItem> it = pendingItems.iterator();

        while (it.hasNext()) {
            final BlockItem item = it.next();
            blockItems.add(item);
            it.remove();

            if (item.hasBlockHeader()) {
                logger.trace("[Block {}] Block header packed in request #{}", blockNumber, index);
                headerItemInfo.packedInRequest(index);
            } else if (item.hasStateChanges()) {
                logger.trace("[Block {}] Block state changes packed in request #{}", blockNumber, index);
                preProofItemInfo.packedInRequest(index);
            } else if (item.hasBlockProof()) {
                logger.trace("[Block {}] Block proof packed in request #{}", blockNumber, index);
                proofItemInfo.packedInRequest(index);
            }

            if (!it.hasNext() || blockItems.size() == maxItems) {
                break;
            }
        }

        final BlockItemSet bis =
                BlockItemSet.newBuilder().blockItems(blockItems).build();
        final PublishStreamRequest psr =
                PublishStreamRequest.newBuilder().blockItems(bis).build();
        final RequestWrapper rs = new RequestWrapper(index, psr, new AtomicBoolean(false));
        requestsByIndex.put(index, rs);

        logger.debug("[Block {}] Created new request (index={}, numItems={})", blockNumber, index, blockItems.size());

        if (!pendingItems.isEmpty()) {
            processPendingItems(batchSize);
        }
    }

    /**
     * @return true if the proof for this block has been sent to a block node, else false
     */
    public boolean isBlockProofSent() {
        return ItemState.SENT == proofItemInfo.state.get();
    }

    /**
     * Mark the request, specified by the index, as being successfully sent to a block node.
     *
     * @param requestIndex the index of the request to mark as sent
     */
    public void markRequestSent(final int requestIndex) {
        final RequestWrapper wrapper = requestsByIndex.get(requestIndex);
        if (wrapper == null) {
            throw new IllegalArgumentException("Invalid request index: " + requestIndex);
        }
        wrapper.isSent.set(true);

        // update if the block proof was sent as part of the request
        if (requestIndex == proofItemInfo.requestIndex.get()) {
            proofItemInfo.state.set(ItemState.SENT);
        }
    }

    @Override
    public String toString() {
        return "BlockState {"
                + "blockNumber=" + blockNumber
                + ", closedTimestamp=" + closedTimestamp.get()
                + ", numPendingItems=" + pendingItems.size()
                + ", numRequests=" + requestsByIndex.size()
                + ", blockHeader=" + headerItemInfo
                + ", blockPreProof=" + preProofItemInfo
                + ", blockProof=" + proofItemInfo
                + "}";
    }
}
