// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
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

    /**
     * Enum representing the state of a block item.
     */
    enum ItemState {
        /**
         * The item hasn't been encountered yet.
         */
        NIL,
        /**
         * The item has been added to the pending items queue, but not yet included in a request.
         */
        ADDED,
        /**
         * The item has been included - or packed - into a request.
         */
        PACKED,
        /**
         * The item has been sent to a block node.
         */
        SENT
    }

    /**
     * Simple record for tracking request information, in particular if a given request has been sent to a block node.
     *
     * @param index the index (starting with 0) of the request
     * @param request the actual request object that gets sent to the block node
     * @param isSent flag indicating if this request has been sent to a block node
     */
    record RequestWrapper(int index, PublishStreamRequest request, AtomicBoolean isSent) {}

    /**
     * Record for capturing information specific to a item, such as what request it is associated with and what state
     * the item is in.
     *
     * @param state the current state of the item (e.g. not seen vs sent)
     * @param requestIndex the index of the request the item is associated with, else -1 if it hasn't been assigned to
     *                     a request yet
     */
    record ItemInfo(AtomicReference<ItemState> state, AtomicInteger requestIndex) {
        ItemInfo() {
            this(new AtomicReference<>(ItemState.NIL), new AtomicInteger(-1));
        }

        /**
         * Marks this item as added to the block state.
         */
        boolean addedInBlockState() {
            return state.compareAndSet(ItemState.NIL, ItemState.ADDED);
        }

        /**
         * Marks this item as being packed in the specified request.
         *
         * @param requestIdx index of the request in which this item was included - or packed - in
         */
        boolean packedInRequest(final int requestIdx) {
            if (state.compareAndSet(ItemState.ADDED, ItemState.PACKED)) {
                requestIndex.set(requestIdx);
                return true;
            }
            return false;
        }
    }

    /**
     * The block number associated with this object.
     */
    private final long blockNumber;
    /**
     * Queue of items that are added to this block, but haven't yet been included in a request for delivery. As items
     * are added requests, they will be removed from this queue. Note: This must be a FIFO (first-in, first-out)
     * structure to ensure ordering.
     */
    private final Queue<BlockItem> pendingItems = new ConcurrentLinkedQueue<>();
    /**
     * Map containing requests generated for this block. The key is the request index (starting with 0) and the value
     * is the wrapped request.
     */
    private final ConcurrentMap<Integer, RequestWrapper> requestsByIndex = new ConcurrentHashMap<>();
    /**
     * Counter used to determine the index of requests created.
     */
    private final AtomicInteger requestIdxCtr = new AtomicInteger(0);
    /**
     * The timestamp in which this block was closed. A closed block should not receive any more items and is considered
     * "final".
     */
    private final AtomicReference<Instant> closedTimestamp = new AtomicReference<>();
    /**
     * Object to track the state of the block header item.
     */
    private final ItemInfo headerItemInfo = new ItemInfo();
    /**
     * Object to track the state of the block proof item.
     */
    private final ItemInfo proofItemInfo = new ItemInfo();
    /**
     * Object to track the state of the pre-proof state change item. This item marks the last item before the block
     * proof is generated.
     */
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

        if (item.hasBlockHeader() && !headerItemInfo.addedInBlockState()) {
            logger.warn(
                    "[Block {}] Block header item added, but block header already encountered (state={})",
                    blockNumber,
                    headerItemInfo.state.get());
        } else if (item.hasBlockProof() && !proofItemInfo.addedInBlockState()) {
            logger.warn(
                    "[Block {}] Block proof item added, but block proof already encountered (state={})",
                    blockNumber,
                    proofItemInfo.state.get());
        } else if (item.hasStateChanges()
                && isPreProofItemReceived(item.stateChangesOrElse(StateChanges.DEFAULT))
                && !preProofItemInfo.addedInBlockState()) {
            logger.warn(
                    "[Block {}] Pre-proof state change item added, but pre-proof state change already encountered (state={})",
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

        if (closedTimestamp.compareAndSet(null, now)) {
            logger.debug("[Block {}] closed at {}", blockNumber, now);
        } else {
            logger.warn(
                    "[Block {}] Attempted to close block at {}, but this block was already closed at {}. "
                            + "Ignoring new close attempt.",
                    blockNumber,
                    now,
                    closedTimestamp.get());
        }
    }

    public void closeBlock(final Instant timestamp) {
        closedTimestamp.set(timestamp);
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
     * Retrieves whether this block has been marked closed.
     *
     * @return true if the block has been closed, else false
     */
    public boolean isClosed() {
        return closedTimestamp.get() != null;
    }

    /**
     * Processes any pending items associated with this block and assigns them to one or more requests that can be sent
     * to a block node.
     *
     * @param batchSize the maximum number of items to include in the request; if this value is less than 1 then the
     *                  batch size is set to 1
     */
    public synchronized void processPendingItems(final int batchSize) {
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

        boolean forceCreation = false;
        while (it.hasNext()) {
            final BlockItem item = it.next();
            blockItems.add(item);
            it.remove();

            if (item.hasBlockHeader()) {
                if (headerItemInfo.packedInRequest(index)) {
                    logger.trace("[Block {}] Block header packed in request #{}", blockNumber, index);
                } else {
                    logger.warn(
                            "[Block {}] Block header item was not yet added (state={})",
                            blockNumber,
                            headerItemInfo.state.get());
                }
            } else if (item.hasStateChanges()
                    && isPreProofItemReceived(item.stateChangesOrElse(StateChanges.DEFAULT))) {
                if (preProofItemInfo.packedInRequest(index)) {
                    forceCreation = true;
                    logger.trace("[Block {}] Pre-proof block state change packed in request #{}", blockNumber, index);
                } else {
                    logger.warn(
                            "[Block {}] Pre-proof block state change was not yet added (state={})",
                            blockNumber,
                            preProofItemInfo.state.get());
                }
            } else if (item.hasBlockProof()) {
                if (proofItemInfo.packedInRequest(index)) {
                    forceCreation = true;
                    logger.trace("[Block {}] Block proof packed in request #{}", blockNumber, index);
                } else {
                    logger.warn(
                            "[Block {}] Block proof was not yet added (state={})",
                            blockNumber,
                            proofItemInfo.state.get());
                }
            }

            if (!it.hasNext() || blockItems.size() == maxItems || forceCreation) {
                break;
            }
        }

        final BlockItemSet bis =
                BlockItemSet.newBuilder().blockItems(blockItems).build();
        final PublishStreamRequest psr =
                PublishStreamRequest.newBuilder().blockItems(bis).build();

        final RequestWrapper rs = new RequestWrapper(index, psr, new AtomicBoolean(false));
        requestsByIndex.put(index, rs);

        logger.trace("[Block {}] Created new request (index={}, numItems={})", blockNumber, index, blockItems.size());

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

    /**
     * Checks if the specified state changes contains block stream info value, which is an indication that all non-proof
     * items have been submitted for the block and only the block proof is remaining.
     *
     * @param stateChanges the changes associated with the item
     * @return true if this state changes include the block stream info value, else false
     */
    private boolean isPreProofItemReceived(@NonNull final StateChanges stateChanges) {
        return stateChanges.stateChanges().stream()
                .map(StateChange::singletonUpdate)
                .filter(Objects::nonNull)
                .anyMatch(update -> update.hasBlockStreamInfoValue()
                        && update.blockStreamInfoValueOrElse(BlockStreamInfo.DEFAULT)
                                        .blockNumber()
                                != -1);
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
