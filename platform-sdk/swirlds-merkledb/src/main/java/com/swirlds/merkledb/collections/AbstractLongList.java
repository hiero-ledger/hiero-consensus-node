// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static com.swirlds.base.units.UnitConstants.MEBIBYTES_TO_BYTES;
import static com.swirlds.merkledb.utilities.MerkleDbFileUtils.readFromFileChannel;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.utilities.MerkleDbFileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BooleanSupplier;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;

/**
 * Common parent class for long list implementations. It takes care of loading a snapshot from disk,
 * chunk management and other common functionality.
 *
 * @param <C> a type that represents a chunk (byte buffer, array or long that represents an offset of the chunk)
 */
public abstract class AbstractLongList<C> implements LongList {

    public static final String MAX_CHUNKS_EXCEEDED_MSG = "The maximum number of memory chunks should not exceed %s. "
            + "Either increase longsPerChunk or decrease capacity";
    public static final String CHUNK_SIZE_ZERO_OR_NEGATIVE_MSG = "Cannot store %d per chunk (min is 1)";
    public static final String CHUNK_SIZE_EXCEEDED_MSG = "Cannot store %d per chunk (max is %d)";
    public static final String INVALID_RANGE_MSG = "Invalid range %d - %d";
    public static final String MAX_VALID_INDEX_LIMIT = "Max valid index %d must be less than max capacity %d";

    /**
     * The maximum number of longs to store per chunk. The value is to make sure a single chunk is
     * less than 2Gb - there is no specific requirement about it, just looks reasonable.
     */
    protected static final int MAX_NUM_LONGS_PER_CHUNK = toIntExact(2_000L * (MEBIBYTES_TO_BYTES / Long.BYTES));

    /**
     * The maximum number of chunks.
     */
    public static final int MAX_NUM_CHUNKS = 2 << 20;

    /** File format that supports min valid index */
    private static final int MIN_VALID_INDEX_SUPPORT_VERSION = 2;

    /** File format with no capacity / longs per chunk info */
    private static final int NO_CAPACITY_VERSION = 3;

    /** The version number for format of current data files */
    private static final int CURRENT_FILE_FORMAT_VERSION = NO_CAPACITY_VERSION;

    /** The number of bytes required to store file version */
    protected static final int VERSION_METADATA_SIZE = Integer.BYTES;

    /** The number of bytes to read for format metadata, v2:
     * - number of longs per chunk<br>
     * - max number of longs supported by the list (capacity) <br>
     * - min valid index<br>
     */
    protected static final int FORMAT_METADATA_SIZE_V2 = Integer.BYTES + Long.BYTES + Long.BYTES;

    /** The number of bytes to read for format metadata, v3:
     * - min valid index<br>
     */
    protected static final int FORMAT_METADATA_SIZE_V3 = Long.BYTES;

    /** The number for bytes to read for file header, v2 */
    protected static final int FILE_HEADER_SIZE_V2 = VERSION_METADATA_SIZE + FORMAT_METADATA_SIZE_V2;

    /** The number for bytes to read for file header, v3 */
    protected static final int FILE_HEADER_SIZE_V3 = VERSION_METADATA_SIZE + FORMAT_METADATA_SIZE_V3;

    /**
     * The number of longs to store in each allocated buffer. Must be a positive integer. If the
     * value is small, then we will end up allocating a very large number of buffers. If the value
     * is large, then we will waste a lot of memory in the unfilled buffer.
     */
    protected final int longsPerChunk;

    /**
     * Size in bytes for each memory chunk to allocate.
     */
    protected final int memoryChunkSize;

    /**
     * The number of longs that this list would contain if it was not optimized by {@link LongList#updateValidRange}.
     * Practically speaking, it defines the list's right boundary.
     */
    protected final AtomicLong size = new AtomicLong(0);

    /**
     * The maximum number of longs to ever store in this data structure. This is used as a safety
     * measure to make sure no bug causes an out of memory issue by causing us to allocate more
     * buffers than the system can handle.
     */
    protected final long capacity;

    /**
     * Min valid index of the list. All indices to the left of this index have {@code IMPERMISSIBLE_VALUE}-s.
     * If the list is empty, both min and max valid indices are -1.
     */
    protected final AtomicLong minValidIndex = new AtomicLong(-1);

    /**
     * Max valid index of the list. All indices to the right of this index have {@code IMPERMISSIBLE_VALUE}-s.
     * If the list is empty, both min and max valid indices are -1.
     */
    protected final AtomicLong maxValidIndex = new AtomicLong(-1);

    /** Atomic reference array of our memory chunks */
    protected final AtomicReferenceArray<C> chunkList;

    /**
     * A length of a buffer that is reserved to remain intact after memory optimization that is
     * happening in {@link LongList#updateValidRange}
     */
    protected final long reservedBufferSize;

    /**
     * Create a new long list with the specified capacity. Number of longs per chunk and
     * reserved buffer size are read from the provided configuration.
     *
     * @param capacity Maximum number of longs permissible for this long list
     * @param configuration Platform configuration
     */
    protected AbstractLongList(final long capacity, final Configuration configuration) {
        final MerkleDbConfig merkleDbConfig = configuration.getConfigData(MerkleDbConfig.class);
        checkCapacity(capacity);
        this.capacity = capacity;
        this.longsPerChunk = merkleDbConfig.longListChunkSize();
        this.reservedBufferSize = merkleDbConfig.longListReservedBufferSize();

        chunkList = new AtomicReferenceArray<>(calculateNumberOfChunks(capacity));
        // multiplyExact throws exception if we overflow and int
        memoryChunkSize = Math.multiplyExact(this.longsPerChunk, Long.BYTES);
    }

    /**
     * Create a new long list with the specified chunk size, capacity, and reserved
     * buffer size.
     *
     * @param longsPerChunk Number of longs to store in each chunk of memory allocated
     * @param capacity Maximum number of longs permissible for this long list
     * @param reservedBufferSize Reserved buffer length that the list should have before
     *                           minimal index in the list
     */
    protected AbstractLongList(final int longsPerChunk, final long capacity, final long reservedBufferSize) {
        checkCapacity(capacity);
        this.capacity = capacity;
        checkLongsPerChunk(longsPerChunk);
        this.longsPerChunk = longsPerChunk;
        this.reservedBufferSize = reservedBufferSize;

        chunkList = new AtomicReferenceArray<>(calculateNumberOfChunks(capacity));
        // multiplyExact throws exception if we overflow and int
        memoryChunkSize = Math.multiplyExact(this.longsPerChunk, Long.BYTES);
    }

    /**
     * Create a new long list from a file that was saved and the specified capacity. Number of
     * longs per chunk and reserved buffer size are read from the provided configuration.
     *
     * <p>If the list size in the file is greater than the capacity, an {@link IllegalArgumentException}
     * is thrown.
     *
     * @param file The file to load the long list from
     * @param capacity Maximum number of longs permissible for this long list
     * @param configuration Platform configuration
     *
     * @throws IOException If the file doesn't exist or there was a problem reading the file
     */
    public AbstractLongList(@NonNull final Path file, final long capacity, @NonNull final Configuration configuration)
            throws IOException {
        final MerkleDbConfig merkleDbConfig = configuration.getConfigData(MerkleDbConfig.class);
        checkCapacity(capacity);
        this.capacity = capacity;
        this.longsPerChunk = merkleDbConfig.longListChunkSize();
        this.memoryChunkSize = longsPerChunk * Long.BYTES;
        this.reservedBufferSize = merkleDbConfig.longListReservedBufferSize();

        chunkList = new AtomicReferenceArray<>(calculateNumberOfChunks(this.capacity));
        loadFromFile(file, configuration);
    }

    /**
     * Create a long list from the specified file with the specified chunk size, capacity, and reserved
     * buffer size. The file must exist.
     *
     * <p>If the list size in the file is greater than the capacity, an {@link IllegalArgumentException}
     * is thrown.
     *
     * @param path The file to load the long list from
     * @param longsPerChunk Number of longs to store in each chunk
     * @param capacity Maximum number of longs permissible for this long list
     * @param reservedBufferSize Reserved buffer length that the list should have before minimal index in the list
     * @param configuration Platform configuration
     *
     * @throws IOException If the file doesn't exist or there was a problem reading the file
     */
    protected AbstractLongList(
            @NonNull final Path path,
            final int longsPerChunk,
            final long capacity,
            final long reservedBufferSize,
            @NonNull final Configuration configuration)
            throws IOException {
        this.longsPerChunk = longsPerChunk;
        this.memoryChunkSize = longsPerChunk * Long.BYTES;
        this.capacity = capacity;
        this.reservedBufferSize = reservedBufferSize;

        chunkList = new AtomicReferenceArray<>(calculateNumberOfChunks(this.capacity));
        loadFromFile(path, configuration);
    }

    private void loadFromFile(@NonNull final Path file, @NonNull Configuration configuration) throws IOException {
        requireNonNull(file);
        requireNonNull(configuration);
        if (!Files.exists(file)) {
            throw new IOException("Cannot load index, file doesn't exist: " + file.toAbsolutePath());
        }
        try (final FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            // read header from existing file
            final ByteBuffer versionBuffer = readFromFileChannel(fileChannel, VERSION_METADATA_SIZE);
            final int formatVersion = versionBuffer.getInt();
            final int formatMetadataSize;
            final int currentFileHeaderSize;
            if (formatVersion == MIN_VALID_INDEX_SUPPORT_VERSION) {
                formatMetadataSize = FORMAT_METADATA_SIZE_V2;
                currentFileHeaderSize = FILE_HEADER_SIZE_V2;
            } else if (formatVersion == NO_CAPACITY_VERSION) {
                formatMetadataSize = FORMAT_METADATA_SIZE_V3;
                currentFileHeaderSize = FILE_HEADER_SIZE_V3;
            } else {
                throw new IOException("File format version is not supported. File format version ["
                        + formatVersion
                        + "], the latest supported version is ["
                        + CURRENT_FILE_FORMAT_VERSION
                        + "].");
            }

            final ByteBuffer headerBuffer = readFromFileChannel(fileChannel, formatMetadataSize);
            if (formatVersion == MIN_VALID_INDEX_SUPPORT_VERSION) {
                headerBuffer.getInt(); // ignored: longs per chunk
            }

            if (formatVersion == MIN_VALID_INDEX_SUPPORT_VERSION) {
                headerBuffer.getLong(); // ignored: capacity
            }

            // Compute how many longs are in the file body
            final long longsInFile = (fileChannel.size() - currentFileHeaderSize) / Long.BYTES;

            final long readMinValidIndex = headerBuffer.getLong();
            // If the file is empty or readMinValidIndex < 0, treat it as an empty list
            if (longsInFile <= 0 || readMinValidIndex < 0) {
                size.set(0);
                minValidIndex.set(-1);
                maxValidIndex.set(-1);
            } else {
                // Otherwise, compute the size by "inflating" it to include the number of indices to the left of
                // the min valid index.
                minValidIndex.set(readMinValidIndex);
                size.set(readMinValidIndex + longsInFile);
                maxValidIndex.set(size.get() - 1);
            }

            if (size.get() > capacity) {
                throw new IllegalArgumentException(
                        "Failed to read index from file, " + "size=" + size.get() + ", capacity=" + capacity);
            }

            readBodyFromFileChannelOnInit(file.getFileName().toString(), fileChannel, configuration);
        }
    }

    /**
     * Initializes the list from the given file channel. At the moment of the call all the class metadata
     * is already initialized from the file header.
     *
     * @param sourceFileName the name of the file from which the list is initialized
     * @param fileChannel the file channel to read the list body from
     * @throws IOException if there was a problem reading the file
     */
    protected void readBodyFromFileChannelOnInit(
            final String sourceFileName, final FileChannel fileChannel, final Configuration configuration)
            throws IOException {
        if (minValidIndex.get() < 0) {
            // Empty list, nothing to read
            return;
        }

        final int firstChunkIndex = toIntExact(minValidIndex.get() / longsPerChunk);
        final int lastChunkIndex = toIntExact(maxValidIndex.get() / longsPerChunk);
        final int minValidIndexInChunk = toIntExact(minValidIndex.get() % longsPerChunk);
        final int maxValidIndexInChunk = toIntExact(maxValidIndex.get() % longsPerChunk);

        for (int chunkIndex = firstChunkIndex; chunkIndex <= lastChunkIndex; chunkIndex++) {
            final int startIndexInChunk = (chunkIndex == firstChunkIndex) ? minValidIndexInChunk : 0;
            final int endIndexInChunk = (chunkIndex == lastChunkIndex) ? (maxValidIndexInChunk + 1) : longsPerChunk;

            C chunk = readChunkData(fileChannel, chunkIndex, startIndexInChunk, endIndexInChunk);
            setChunk(chunkIndex, chunk);
        }
    }

    /**
     * Reads data from the specified {@code fileChannel} and stores it into a chunk.
     * The data is read from the specified range within the chunk.
     * Subclasses must implement this method to read data from the provided {@code fileChannel}.
     *
     * @param fileChannel the file channel to read from
     * @param chunkIndex the index of the chunk to store the read data
     * @param startIndex the starting index (inclusive) within the chunk
     * @param endIndex the ending index (exclusive) within the chunk
     * @return a chunk (byte buffer, array or long that represents an offset of the chunk)
     * @throws IOException if there is an error reading the file
     */
    protected abstract C readChunkData(FileChannel fileChannel, int chunkIndex, int startIndex, int endIndex)
            throws IOException;

    /**
     * Stores the specified chunk at the given {@code chunkIndex}.
     *
     * @param chunkIndex the index where the chunk is to be stored
     * @param chunk      the chunk to store
     */
    protected void setChunk(int chunkIndex, C chunk) {
        chunkList.set(chunkIndex, chunk);
    }

    /**
     * Reads a specified range of elements from a file channel into the given buffer, starting from
     * the given start index (inc) and up to the given end index (exc).
     * <p>
     * This method computes the appropriate byte offsets within the buffer and the number of bytes
     * to read based on the provided {@code startIndex} and {@code endIndex}. It then performs a
     * complete read of that data from the file channel into the buffer.
     *
     * @param fileChannel the file channel to read data from
     * @param chunkIndex the index of the chunk being read
     * @param startIndex the starting index (inclusive) within the chunk of the first element to read
     * @param endIndex the ending index (exclusive) within the chunk of the last element to read
     * @param buffer the buffer into which data will be read
     * @throws IOException if an error occurs while reading from the file,
     * or if the number of bytes read does not match the expected size
     */
    protected static void readDataIntoBuffer(
            final FileChannel fileChannel,
            final int chunkIndex,
            final int startIndex,
            final int endIndex,
            final ByteBuffer buffer)
            throws IOException {
        final int startOffset = startIndex * Long.BYTES;
        final int endOffset = endIndex * Long.BYTES;

        buffer.position(startOffset);
        buffer.limit(endOffset);

        final int bytesToRead = endOffset - startOffset;
        final long bytesRead = MerkleDbFileUtils.completelyRead(fileChannel, buffer);
        if (bytesRead != bytesToRead) {
            throw new IOException("Failed to read chunks, chunkIndex=" + chunkIndex + " expected=" + bytesToRead
                    + " actual=" + bytesRead);
        }
    }

    /**
     * Loads the long at the given index.
     *
     * @param index        the index of the long
     * @param defaultValue The value to return if nothing is stored for the long
     * @return the loaded long
     * @throws IndexOutOfBoundsException if the index is negative or beyond current capacity of the list
     */
    @Override
    public long get(final long index, final long defaultValue) {
        if (index < 0 || index >= capacity) {
            throw new IndexOutOfBoundsException(index);
        }
        if (index >= size.get()) {
            return defaultValue;
        }
        final int chunkIndex = toIntExact(index / longsPerChunk);
        final long subIndex = index % longsPerChunk;
        final C chunk = chunkList.get(chunkIndex);
        if (chunk == null) {
            return defaultValue;
        }
        final long presentValue = lookupInChunk(chunk, subIndex);
        return presentValue == IMPERMISSIBLE_VALUE ? defaultValue : presentValue;
    }

    /**
     * Stores a long in the list at the given index.
     *
     * @param index the index to use
     * @param value the long to store
     * @throws IndexOutOfBoundsException if the index is negative or beyond the max capacity of the list
     * @throws IllegalArgumentException if old value is zero (which could never be true)
     */
    @Override
    public final void put(long index, long value) {
        checkIndex(index);
        checkValue(value);
        putImpl(index, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void remove(final long index) {
        checkIndex(index);
        putImpl(index, IMPERMISSIBLE_VALUE);
    }

    private void putImpl(final long index, final long value) {
        assert index >= minValidIndex.get()
                : String.format("Index %d is less than min valid index %d", index, minValidIndex.get());
        assert index <= maxValidIndex.get()
                : String.format("Index %d is greater than max valid index %d", index, maxValidIndex.get());
        final C chunk = createOrGetChunk(index);
        final int subIndex = toIntExact(index % longsPerChunk);
        putToChunk(chunk, subIndex, value);
    }

    /**
     * Stores a long in the list at the given chunk at subIndex.
     * @param chunk the chunk to use
     * @param subIndex the subIndex to use
     * @param value the long to store
     */
    protected abstract void putToChunk(C chunk, int subIndex, long value);

    /**
     * Stores a long at the given index, on the condition that the current long therein has a given
     * value.
     *
     * @param index the index to use
     * @param oldValue the value that must currently obtain at the index
     * @param newValue the new value to store
     * @return whether the newValue was set
     * @throws IndexOutOfBoundsException if the index is negative or beyond the max capacity of the list
     * @throws IllegalArgumentException if old value is zero (which could never be true)
     */
    @Override
    public final boolean putIfEqual(long index, long oldValue, long newValue) {
        checkIndex(index);
        checkValue(newValue);
        final int chunkIndex = toIntExact(index / longsPerChunk);
        final C chunk = chunkList.get(chunkIndex);
        if (chunk == null) {
            // quick optimization: we can quit early without creating new memory blocks
            // unnecessarily
            return false;
        }
        final int subIndex = toIntExact(index % longsPerChunk);
        boolean result = putIfEqual(chunk, subIndex, oldValue, newValue);
        if (result) {
            // update the size if necessary
            size.getAndUpdate(oldSize -> index >= oldSize ? (index + 1) : oldSize);
        }
        return result;
    }

    /**
     * Stores a long in a given chunk at a given sub index, on the condition that the current long therein has a given
     * value.
     *
     * @param chunk offset of the chunk to use
     * @param subIndex the index within the chunk to use
     * @param oldValue the value that must currently obtain at the index
     * @param newValue the new value to store
     * @return whether the newValue was set
     */
    protected abstract boolean putIfEqual(C chunk, int subIndex, long oldValue, long newValue);

    /**
     * Implements CASable.get(index)
     *
     * @param index position, key, etc.
     * @return read value
     */
    @Override
    public long get(final long index) {
        return get(index, IMPERMISSIBLE_VALUE);
    }

    /** {@inheritDoc} */
    @Override
    public final long capacity() {
        return capacity;
    }

    /** {@inheritDoc} */
    @Override
    public final long size() {
        return size.get();
    }

    /**
     * Get the number of longs in each check of allocated memory.
     *
     * @return Size in longs of memory allocation chunk
     */
    public final int getLongsPerChunk() {
        return longsPerChunk;
    }

    // For testing purposes
    final long getReservedBufferSize() {
        return reservedBufferSize;
    }

    /**
     * Create a stream over the data in this LongList. This is designed for testing and may be
     * inconsistent under current modifications.
     */
    @Override
    public LongStream stream() {
        return StreamSupport.longStream(new LongListSpliterator(this), false);
    }

    /**
     * Write all longs in this LongList into a file
     * <p>
     * <b> It is not guaranteed what version of data will be written if the LongList is changed
     * via put methods while this LongList is being written to a file. If you need consistency while
     * calling put concurrently then use a BufferedLongListWrapper. </b>
     *
     * @param file The file to write into, it should not exist but its parent directory should exist
     *             and be writable.
     * @throws IOException If there was a problem creating or writing to the file.
     */
    @Override
    public void writeToFile(final Path file) throws IOException {
        try (final FileChannel fc = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            // write header
            writeHeader(fc);
            if (size() > 0) {
                // write data
                writeLongsData(fc);
            }
            fc.force(true);
        }
    }

    /**
     * Write or rewrite header in file
     *
     * @param fc File channel on the file to write to
     * @throws IOException If there was a problem writing header
     */
    protected final void writeHeader(final FileChannel fc) throws IOException {
        final int currentFileHeaderSize = FILE_HEADER_SIZE_V3;
        final ByteBuffer headerBuffer = ByteBuffer.allocate(currentFileHeaderSize);
        headerBuffer.rewind();
        headerBuffer.putInt(CURRENT_FILE_FORMAT_VERSION);
        headerBuffer.putLong(minValidIndex.get());
        // maxValidIndex is not written. On loading, it will be set automatically based on the size
        headerBuffer.flip();
        // always write at start of file
        if (MerkleDbFileUtils.completelyWrite(fc, headerBuffer, 0) != currentFileHeaderSize) {
            throw new IOException("Failed to write long list header to the file channel " + fc);
        }
        fc.position(currentFileHeaderSize);
    }

    /**
     * Write the long data to file, This it is expected to be in one simple block of raw longs.
     *
     * @param fc The file channel to write to
     * @throws IOException if there was a problem writing longs
     */
    protected abstract void writeLongsData(final FileChannel fc) throws IOException;

    /**
     * Lookup a long in data
     *
     * @param chunk chunk to lookup in
     * @param subIndex   The sub index of the long in that chunk
     * @return The stored long value at given index
     */
    protected abstract long lookupInChunk(@NonNull final C chunk, final long subIndex);

    /** {@inheritDoc} */
    @Override
    public final void updateValidRange(final long newMinValidIndex, final long newMaxValidIndex) {
        if ((newMinValidIndex < -1) || (newMinValidIndex > newMaxValidIndex)) {
            throw new IndexOutOfBoundsException(INVALID_RANGE_MSG.formatted(newMinValidIndex, newMaxValidIndex));
        }
        if (newMaxValidIndex >= capacity) {
            throw new IndexOutOfBoundsException(MAX_VALID_INDEX_LIMIT.formatted(newMaxValidIndex, capacity));
        }

        minValidIndex.set(newMinValidIndex);
        final long oldMaxValidIndex = maxValidIndex.getAndSet(newMaxValidIndex);
        size.updateAndGet(v -> min(v, newMaxValidIndex + 1));

        shrinkLeftSideIfNeeded(newMinValidIndex);
        shrinkRightSideIfNeeded(oldMaxValidIndex, newMaxValidIndex);
        // everything to the right of the newMaxValidIndex is going to be discarded, adjust the size accordingly
    }

    @Override
    public long getMinValidIndex() {
        return minValidIndex.get();
    }

    @Override
    public long getMaxValidIndex() {
        return maxValidIndex.get();
    }

    /**
     * Expand the available data storage if needed to allow storage of an item at newIndex
     *
     * @param newIndex the index of the new item we would like to add to storage
     */
    protected C createOrGetChunk(final long newIndex) {
        size.getAndUpdate(oldSize -> newIndex >= oldSize ? (newIndex + 1) : oldSize);
        final int chunkIndex = toIntExact(newIndex / longsPerChunk);
        final C result = chunkList.get(chunkIndex);
        if (result == null) {
            final C newChunk = createChunk();
            // set new chunk if it's not created yet, if it is - release the chunk immediately
            // and use the one from the list
            final C oldChunk = chunkList.compareAndExchange(chunkIndex, null, newChunk);
            if (oldChunk == null) {
                return newChunk;
            } else {
                closeChunk(newChunk);
                return oldChunk;
            }
        } else {
            return result;
        }
    }

    /**
     * Deletes values up to {@code newMinValidIndex} and releases memory chunks reserved for these values.
     * @param newMinValidIndex new minimal valid index, left boundary of the list
     */
    private void shrinkLeftSideIfNeeded(final long newMinValidIndex) {
        final int firstValidChunkWithBuffer =
                toIntExact(max((newMinValidIndex - reservedBufferSize) / longsPerChunk, 0));
        final int firstChunkIndexToDelete = firstValidChunkWithBuffer - 1;
        for (int i = firstChunkIndexToDelete; i >= 0; i--) {
            final C chunk = chunkList.get(i);
            if (chunk != null && chunkList.compareAndSet(i, chunk, null)) {
                closeChunk(chunk);
            }
        }

        // clean up a chunk with data
        final int firstChunkWithDataIndex = toIntExact((newMinValidIndex / longsPerChunk));
        final long numberOfElementsToCleanUp = (newMinValidIndex % longsPerChunk);
        C chunk = chunkList.get(firstChunkWithDataIndex);
        if (chunk != null && numberOfElementsToCleanUp > 0) {
            partialChunkCleanup(chunk, true, numberOfElementsToCleanUp);
        }

        // clean up chunk(s) reserved for buffer
        for (int i = firstValidChunkWithBuffer; i < firstChunkWithDataIndex; i++) {
            chunk = chunkList.get(i);
            if (chunk != null) {
                partialChunkCleanup(chunk, true, longsPerChunk);
            }
        }
    }

    /**
     * Deletes values from {@code newMaxValidIndex} to the end of the list and releases memory chunks reserved for these values.
     *
     * @param oldMaxValidIndex old maximal valid index, former right boundary of the list
     * @param newMaxValidIndex new maximal valid index, new right boundary of the list
     */
    private void shrinkRightSideIfNeeded(long oldMaxValidIndex, long newMaxValidIndex) {
        final int listLength = chunkList.length();
        final int lastValidChunkWithBufferIndex =
                toIntExact(min((newMaxValidIndex + reservedBufferSize) / longsPerChunk, listLength - 1));
        final int firstChunkIndexToDelete = lastValidChunkWithBufferIndex + 1;
        final int numberOfChunks = calculateNumberOfChunks(oldMaxValidIndex);

        for (int i = firstChunkIndexToDelete; i < numberOfChunks; i++) {
            final C chunk = chunkList.get(i);
            if (chunk != null && chunkList.compareAndSet(i, chunk, null)) {
                closeChunk(chunk);
            }
        }

        // clean up a chunk with data
        final int firstChunkWithDataIndex = toIntExact(newMaxValidIndex / longsPerChunk);
        final long numberOfEntriesToCleanUp = longsPerChunk - (newMaxValidIndex % longsPerChunk) - 1;
        C chunk = chunkList.get(firstChunkWithDataIndex);
        if (chunk != null && numberOfEntriesToCleanUp > 0) {
            partialChunkCleanup(chunk, false, numberOfEntriesToCleanUp);
        }

        // clean up chunk(s) reserved for buffer
        for (int i = firstChunkWithDataIndex + 1; i <= lastValidChunkWithBufferIndex; i++) {
            chunk = chunkList.get(i);
            if (chunk != null) {
                partialChunkCleanup(chunk, false, longsPerChunk);
            }
        }
    }

    /**
     * Zeroes out a part of a chunk.
     * @param chunk index of the chunk to clean up
     * @param leftSide         if true, cleans up {@code entriesToCleanUp} on the left side of the chunk,
     *                         if false, cleans up {@code entriesToCleanUp} on the right side of the chunk
     * @param entriesToCleanUp number of entries to clean up
     */
    protected abstract void partialChunkCleanup(
            @NonNull final C chunk, final boolean leftSide, final long entriesToCleanUp);

    /**
     * Allocates a new chunk of data.
     *
     * @return a new chunk
     */
    protected abstract C createChunk();

    /**
     * Releases a chunk. This method is called for every chunk when this list is closed. It's
     * also used to delete chunks, when they are no longer in use because of min/max valid
     * index updated.
     *
     * @param chunk the chunk to clean up
     */
    protected void closeChunk(@NonNull final C chunk) {
        // to be overridden
    }

    /**
     * @param totalNumberOfElements total number of elements in the list
     * @return number of memory chunks that this list may have
     */
    protected int calculateNumberOfChunks(final long totalNumberOfElements) {
        final int chunkCount = toIntExact((totalNumberOfElements - 1) / longsPerChunk + 1);
        checkChunkCount(chunkCount);
        return chunkCount;
    }

    /**
     * Checks if a value may be put into a LongList at a certain index, given a max capacity.
     *
     * @param index the index to check
     * @throws IndexOutOfBoundsException if the index is out-of-bounds
     */
    private void checkIndex(final long index) {
        if (index < 0 || index >= capacity) {
            throw new IndexOutOfBoundsException("Index " + index + " is out-of-bounds given capacity " + capacity);
        }
    }

    /**
     * Checks if the value may be put into a LongList.
     *
     * @param value the value to check
     * @throws IllegalArgumentException  if the value is impermissible
     */
    private void checkValue(final long value) {
        if (value == IMPERMISSIBLE_VALUE) {
            throw new IllegalArgumentException("Cannot put " + IMPERMISSIBLE_VALUE + " into a LongList");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>This long list implementation checks the condition (if not null) before every list
     * item is processed.
     */
    @Override
    public <T extends Throwable> boolean forEach(
            @NonNull final LongAction<T> action, @Nullable final BooleanSupplier cond) throws InterruptedException, T {
        final long max = maxValidIndex.get();
        if (max < 0) {
            // Empty list, nothing to do
            return true;
        }
        Objects.requireNonNull(action);
        final BooleanSupplier condition = cond != null ? cond : () -> true;
        long i = minValidIndex.get();
        for (; (i <= max) && condition.getAsBoolean(); i++) {
            final long value = get(i);
            if (value != IMPERMISSIBLE_VALUE) {
                action.handle(i, value);
            }
        }
        return i == max + 1;
    }

    /**
     * This method returns a snapshot of the current data. FOR TEST PURPOSES ONLY. NOT
     * THREAD SAFE
     *
     * @return a copy of data.
     */
    List<C> dataCopy() {
        final ArrayList<C> result = new ArrayList<>();
        for (int i = 0; i < chunkList.length(); i++) {
            result.add(chunkList.get(i));
        }
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        size.set(0);
        for (int i = 0; i < chunkList.length(); i++) {
            final C chunk = chunkList.getAndSet(i, null);
            if (chunk != null) {
                closeChunk(chunk);
            }
        }
    }

    private void checkCapacity(final long capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("The maximum number of longs must be non-negative, not " + capacity);
        }
    }

    private void checkLongsPerChunk(final long longsPerChunk) {
        if (longsPerChunk <= 0) {
            throw new IllegalArgumentException(CHUNK_SIZE_ZERO_OR_NEGATIVE_MSG.formatted(longsPerChunk));
        }
        if (longsPerChunk > MAX_NUM_LONGS_PER_CHUNK) {
            throw new IllegalArgumentException(
                    CHUNK_SIZE_EXCEEDED_MSG.formatted(longsPerChunk, MAX_NUM_LONGS_PER_CHUNK));
        }
    }

    private void checkChunkCount(final int chunkCount) {
        if (chunkCount > MAX_NUM_CHUNKS) {
            throw new IllegalArgumentException(MAX_CHUNKS_EXCEEDED_MSG.formatted(MAX_NUM_CHUNKS));
        }
    }
}
