// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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

    /** Number of low-order bits in a protobuf tag occupied by the wire type (the remaining bits are the field number). */
    private static final int PROTOBUF_TAG_TYPE_BITS = 3;

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
     * Map that contains all items associated with this block. Each item is stored in its serialized form (paired with
     * its item type) to reduce memory usage compared to retaining the deserialized {@link BlockItem} object graph. Each
     * item in the map is specified by an integer key that represents the order in which the item was added to the block.
     */
    private final ConcurrentMap<Integer, BufferedItem> bufferedItems = new ConcurrentHashMap<>();
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
     * Adds an item to the block in its serialized form.
     *
     * @param serializedItem the full serialized bytes of a single {@link BlockItem}
     * @param itemType the type of the item being added
     * @throws IllegalStateException if the block is closed
     */
    public void addItem(@Nullable final Bytes serializedItem, @NonNull final BlockItem.ItemOneOfType itemType) {
        if (serializedItem == null) {
            return;
        }
        requireNonNull(itemType, "itemType must not be null");

        if (closedTimestamp != null) {
            throw new IllegalStateException("Block is closed; adding more items is not permitted");
        }

        final int index = itemIndex.incrementAndGet();
        bufferedItems.put(index, new BufferedItem(serializedItem, itemType));
        if (itemType == BlockItem.ItemOneOfType.BLOCK_HEADER) {
            openedNanos = System.nanoTime();
            openedTimestamp = Instant.now();
        }
        sizeBytes += serializedItem.length();
    }

    /**
     * Adds an item to the block in its serialized form, deriving the item type from the leading protobuf tag of the
     * serialized bytes (see {@link #itemTypeOf(Bytes)}). This is intended for the path that restores the buffer from
     * disk, where only the serialized bytes are available — not the deserialized item or its type. The hot path should
     * use {@link #addItem(Bytes, BlockItem.ItemOneOfType)}, which is given the type directly.
     *
     * @param serializedItem the full serialized bytes of a single {@link BlockItem}
     * @throws IllegalStateException if the block is closed
     */
    public void addItem(@Nullable final Bytes serializedItem) {
        if (serializedItem == null) {
            return;
        }
        addItem(serializedItem, itemTypeOf(serializedItem));
    }

    /**
     * Adds a deserialized item to the block. This is a convenience that serializes the item and stores its serialized
     * form; it is intended for non-hot paths and tests. The hot path should use
     * {@link #addItem(Bytes, BlockItem.ItemOneOfType)} to avoid re-serialization.
     *
     * @param item the item to add
     * @throws IllegalStateException if the block is closed
     */
    public void addItem(@Nullable final BlockItem item) {
        if (item == null) {
            return;
        }
        addItem(BlockItem.PROTOBUF.toBytes(item), item.item().kind());
    }

    /**
     * Determines the {@link BlockItem.ItemOneOfType} of a serialized {@link BlockItem} by reading the field number from
     * its leading protobuf tag, without deserializing the item. A {@link BlockItem} contains only its {@code item}
     * oneof, so the first field on the wire is always the set oneof field, and its field number maps one-to-one to the
     * item type.
     *
     * @param serializedItem the full serialized bytes of a single {@link BlockItem}
     * @return the item type, or {@link BlockItem.ItemOneOfType#UNSET} if the bytes are empty (an unset item) or the
     * field number is unrecognized
     */
    static BlockItem.ItemOneOfType itemTypeOf(@NonNull final Bytes serializedItem) {
        requireNonNull(serializedItem, "serializedItem must not be null");
        if (serializedItem.length() == 0L) {
            return BlockItem.ItemOneOfType.UNSET;
        }
        // The leading varint is the protobuf tag: (fieldNumber << 3) | wireType. The field number identifies which
        // oneof field is set, which corresponds 1:1 to the BlockItem item type.
        final int tag = serializedItem.toReadableSequentialData().readVarInt(false);
        return BlockItem.ItemOneOfType.fromProtobufOrdinal(tag >>> PROTOBUF_TAG_TYPE_BITS);
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
     * Retrieve a single buffered (serialized) block item by its index (insertion order).
     *
     * @param index the index of the block item to retrieve
     * @return the buffered block item, or null if no item has the specified index
     */
    public @Nullable BufferedItem bufferedItem(final int index) {
        return bufferedItems.get(index);
    }

    /**
     * Retrieve a single block item by its index (insertion order), deserializing it from its stored bytes. This is a
     * convenience for non-hot paths (e.g. persistence and tests); the hot path should use {@link #bufferedItem(int)} to
     * avoid deserialization.
     *
     * @param index the index of the block item to retrieve
     * @return the block item, or null if no item has the specified index
     */
    public @Nullable BlockItem blockItem(final int index) {
        final BufferedItem item = bufferedItems.get(index);
        if (item == null) {
            return null;
        }
        try {
            return BlockItem.PROTOBUF.parse(item.serializedItem());
        } catch (final ParseException e) {
            throw new RuntimeException("Failed to parse buffered block item at index " + index, e);
        }
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
                && Objects.equals(bufferedItems, that.bufferedItems)
                && Objects.equals(closedTimestamp, that.closedTimestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blockNumber, bufferedItems, closedTimestamp);
    }

    @Override
    public String toString() {
        return "BlockState{" + "blockNumber="
                + blockNumber + ", closedTimestamp="
                + closedTimestamp + ", blockItemCount="
                + bufferedItems.size() + '}';
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

    /**
     * A single block item stored in its serialized form along with the metadata required to stream it to a block node
     * without deserializing it.
     *
     * @param serializedItem the full serialized bytes of a single {@link BlockItem}
     * @param itemType the type of the item
     */
    public record BufferedItem(
            @NonNull Bytes serializedItem, @NonNull BlockItem.ItemOneOfType itemType) {
        public BufferedItem {
            requireNonNull(serializedItem, "serializedItem must not be null");
            requireNonNull(itemType, "itemType must not be null");
        }

        /**
         * @return the size of the serialized item in bytes
         */
        public int size() {
            return (int) serializedItem.length();
        }

        /**
         * @return true if this item is a block header, else false
         */
        public boolean isHeader() {
            return itemType == BlockItem.ItemOneOfType.BLOCK_HEADER;
        }

        /**
         * @return true if this item is a block proof, else false
         */
        public boolean isProof() {
            return itemType == BlockItem.ItemOneOfType.BLOCK_PROOF;
        }
    }
}
