// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A "block state" is the collection of items contained with a single block and can be streamed to a block node.
 */
public class BlockState {

    /**
     * The block number associated with this instance.
     */
    private final long blockNumber;
    /**
     * Counter used to assign an index to a block item. Items are assigned a monotonically increasing integer value
     * that is used to look up the item and to get the items ordered.
     */
    private final AtomicInteger itemIndex = new AtomicInteger(-1);
    /**
     * Map that contains all items associated with this block. Each item in the map is specified by an integer key that
     * represents the order in which the item was added to the block.
     */
    private final ConcurrentMap<Integer, BlockItem> blockItems = new ConcurrentHashMap<>();
    /**
     * The timestamp associated with when this block was closed.
     */
    private volatile Instant closedTimestamp;
    /**
     * The timestamp associated with when this block was opened.
     */
    private volatile Instant openedTimestamp;
    /**
     * The size of the block in bytes.
     */
    private long sizeBytes;
    /**
     * Monotonic nanoTime when this block was opened. Used for latency computation.
     */
    private volatile long openedNanos = -1;
    /**
     * Monotonic nanoTime when this block was closed. Used for latency computation.
     */
    private volatile long closedNanos = -1;
    /**
     * Monotonic nanoTime when the block header was sent. Used for latency computation.
     */
    private volatile long headerSentNanos = -1;
    /**
     * Monotonic nanoTime when the block end was sent. Used for latency computation.
     */
    private volatile long blockEndSentNanos = -1;

    /**
     * Create a new block state object.
     *
     * @param blockNumber the block number associated with this block
     */
    public BlockState(final long blockNumber) {
        this.blockNumber = blockNumber;
    }

    /**
     * Adds an item to the block.
     *
     * @param item the item to add
     * @throws IllegalStateException if the block is closed
     */
    public void addItem(@Nullable final BlockItem item) {
        if (item == null) {
            return;
        }

        if (closedTimestamp != null) {
            throw new IllegalStateException("Block is closed; adding more items is not permitted");
        }

        final int index = itemIndex.incrementAndGet();
        blockItems.put(index, item);
        if (item.hasBlockHeader()) {
            openedNanos = System.nanoTime();
            openedTimestamp = Instant.now();
        }
        sizeBytes += item.protobufSize();
    }

    /**
     * @return the block number associated with this block
     */
    public long blockNumber() {
        return blockNumber;
    }

    /**
     * @return true if the block is closed, else false
     */
    public boolean isClosed() {
        return closedTimestamp != null;
    }

    /**
     * @return the size of the block in bytes
     */
    public long sizeBytes() {
        return sizeBytes;
    }

    /**
     * Closes this block with the current time.
     */
    public void closeBlock() {
        closedNanos = System.nanoTime();
        closeBlock(Instant.now());
    }

    /**
     * Closes this block with the specified time.
     *
     * @param timestamp the timestamp to close the block with
     * @throws NullPointerException if the specified timestamp is null
     */
    public void closeBlock(@NonNull final Instant timestamp) {
        if (closedNanos == -1) {
            closedNanos = System.nanoTime();
        }
        closedTimestamp = requireNonNull(timestamp, "timestamp must not be null");
    }

    /**
     * @return the timestamp of when block was closed, else null if the block is not closed
     */
    public @Nullable Instant closedTimestamp() {
        return closedTimestamp;
    }

    /**
     * @return the timestamp of when block was opened, else null if the block has not been opened
     */
    public @Nullable Instant openedTimestamp() {
        return openedTimestamp;
    }

    /**
     * Retrieve a single block item by its index (insertion order).
     *
     * @param index the index of the block item to retrieve
     * @return the block item, or null if no item has the specified index
     */
    public @Nullable BlockItem blockItem(final int index) {
        return blockItems.get(index);
    }

    /**
     * @return count of the number of items associated with this block
     */
    public int itemCount() {
        return itemIndex.get() + 1;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final BlockState that = (BlockState) o;
        return blockNumber == that.blockNumber
                && Objects.equals(blockItems, that.blockItems)
                && Objects.equals(closedTimestamp, that.closedTimestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blockNumber, blockItems, closedTimestamp);
    }

    @Override
    public String toString() {
        return "BlockState{" + "blockNumber="
                + blockNumber + ", closedTimestamp="
                + closedTimestamp + ", blockItemCount="
                + blockItems.size() + '}';
    }

    /**
     * Sets the opened timestamp for this block.
     * @param openedInstant the timestamp when the block was opened
     */
    public void setOpenedTimestamp(@NonNull final Instant openedInstant) {
        if (openedNanos == -1) {
            openedNanos = System.nanoTime();
        }
        this.openedTimestamp = openedInstant;
    }

    /**
     * Sets the monotonic nanoTime when the block header was sent.
     * @param headerSentNanos the nanoTime when the block header was sent
     */
    public void setHeaderSentNanos(final long headerSentNanos) {
        this.headerSentNanos = headerSentNanos;
    }

    /**
     * Sets the monotonic nanoTime when the block end was sent.
     * @param blockEndSentNanos the nanoTime when the block end was sent
     */
    public void setBlockEndSentNanos(final long blockEndSentNanos) {
        this.blockEndSentNanos = blockEndSentNanos;
    }

    /**
     * @return the monotonic nanoTime when the block was opened, or -1 if not yet opened
     */
    public long openedNanos() {
        return openedNanos;
    }

    /**
     * @return the monotonic nanoTime when the block was closed, or -1 if not yet closed
     */
    public long closedNanos() {
        return closedNanos;
    }

    /**
     * @return the monotonic nanoTime when the block header was sent, or -1 if not yet sent
     */
    public long headerSentNanos() {
        return headerSentNanos;
    }

    /**
     * @return the monotonic nanoTime when the block end was sent, or -1 if not yet sent
     */
    public long blockEndSentNanos() {
        return blockEndSentNanos;
    }
}
