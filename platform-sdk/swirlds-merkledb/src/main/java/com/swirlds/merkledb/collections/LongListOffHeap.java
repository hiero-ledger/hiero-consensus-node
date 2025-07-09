// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNullElse;

import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.utilities.MerkleDbFileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.MemoryUtils;

/**
 * A {@link LongList} that stores its contents off-heap via a {@link AtomicReferenceArray} of direct
 * {@link ByteBuffer}s. Each {@link ByteBuffer} is the same size, so the "chunk" containing the
 * value for any given index is easily found using modular arithmetic. Note that <br>
 * to reduce memory consumption one can use {@link LongList#updateValidRange(long, long)}.
 * A call to this method discards memory chunks reserved for the indices that are before the index
 * passed as an argument subtracted by {@link AbstractLongList#reservedBufferSize}. The idea is to
 * keep the amount of memory defined by {@link AbstractLongList#reservedBufferSize} reserved even
 * though it serves indices that are before the minimal index. It may be a good idea because there
 * is a good chance that the indices in this range may be used (e.g. in case of mass deletion from
 * an instance of {@link com.swirlds.merkledb.files.MemoryIndexDiskKeyValueStore})
 *
 * <p>Per the {@link LongList} contract, this class is thread-safe for both concurrent reads and
 * writes.
 */
public final class LongListOffHeap extends AbstractLongList<ByteBuffer> implements OffHeapUser {

    private static final Logger logger = LogManager.getLogger(LongListOffHeap.class);

    /**
     * Create a new off-heap long list with the specified capacity. Number of longs per chunk and
     * reserved buffer size are read from the provided configuration.
     *
     * @param capacity Maximum number of longs permissible for this long list
     * @param configuration Platform configuration
     */
    public LongListOffHeap(final long capacity, final Configuration configuration) {
        super(capacity, configuration);
    }

    /**
     * Create a new off-heap long list with the specified chunk size, capacity, and reserved
     * buffer size.
     *
     * @param longsPerChunk Number of longs to store in each chunk of memory allocated
     * @param capacity Maximum number of longs permissible for this long list
     * @param reservedBufferSize Reserved buffer length that the list should have before
     *                           minimal index in the list
     */
    public LongListOffHeap(final int longsPerChunk, final long capacity, final long reservedBufferSize) {
        super(longsPerChunk, capacity, reservedBufferSize);
    }

    /**
     * Create a new off-heap long list from a file that was saved and the specified capacity. Number of
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
    public LongListOffHeap(@NonNull final Path file, final long capacity, @NonNull final Configuration configuration)
            throws IOException {
        super(file, capacity, configuration);
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
    public LongListOffHeap(
            @NonNull final Path path,
            final int longsPerChunk,
            final long capacity,
            final long reservedBufferSize,
            @NonNull final Configuration configuration)
            throws IOException {
        super(path, longsPerChunk, capacity, reservedBufferSize, configuration);
    }

    /** {@inheritDoc} */
    @Override
    protected ByteBuffer readChunkData(FileChannel fileChannel, int chunkIndex, int startIndex, int endIndex)
            throws IOException {
        final ByteBuffer chunk = createChunk();
        readDataIntoBuffer(fileChannel, chunkIndex, startIndex, endIndex, chunk);
        // All chunks (byte buffers) in LongListOffHeap are stored with position == 0 and
        // limit == capacity. When this list is written to a file, the first and the last
        // chunk positions and limits are taken care of
        chunk.clear();
        return chunk;
    }

    /** {@inheritDoc} */
    @Override
    protected void closeChunk(@NonNull final ByteBuffer directBuffer) {
        MemoryUtils.closeDirectByteBuffer(directBuffer);
    }

    /** {@inheritDoc} */
    @Override
    protected void putToChunk(ByteBuffer chunk, int subIndex, long value) {
        /* The remaining lines below are equivalent to a chunk.put(subIndex, value) call
        on a heap byte buffer. Since we have instead a direct buffer, we need to, first,
        get its native memory address from the Buffer.address field; and, second, store
        the given long at the appropriate offset from that address. */
        final int subIndexOffset = subIndex * Long.BYTES;
        MemoryUtils.putLongVolatile(chunk, subIndexOffset, value);
    }

    /** {@inheritDoc} */
    @Override
    protected boolean putIfEqual(ByteBuffer chunk, int subIndex, long oldValue, long newValue) {
        /* Below would be equivalent to a compareAndSet(subIndex, oldValue, newValue)
        call on a heap byte buffer, if such a method existed. Since we have instead a
        direct buffer, we need to, first, get its native memory address from the
        Buffer.address field; and, second, compare-and-swap the given long at the
        appropriate offset from that address. */
        final int subIndexBytes = subIndex * Long.BYTES;
        return MemoryUtils.compareAndSwapLong(chunk, subIndexBytes, oldValue, newValue);
    }

    /**
     * Write the long data to file, This it is expected to be in one simple block of raw longs.
     *
     * @param fc The file channel to write to
     * @throws IOException if there was a problem writing longs
     */
    @Override
    protected void writeLongsData(final FileChannel fc) throws IOException {
        final int totalNumOfChunks = calculateNumberOfChunks(size());
        final long currentMinValidIndex = minValidIndex.get();
        final int firstChunkWithDataIndex = toIntExact(currentMinValidIndex / longsPerChunk);
        // write data
        final ByteBuffer emptyBuffer = createChunk();
        try {
            for (int i = firstChunkWithDataIndex; i < totalNumOfChunks; i++) {
                final ByteBuffer byteBuffer = chunkList.get(i);
                final ByteBuffer nonNullBuffer = requireNonNullElse(byteBuffer, emptyBuffer);
                // Slice so we don't mess with the byte buffer pointers.
                // Also, the slice size has to be equal to the size of the buffer
                final ByteBuffer buf = nonNullBuffer.slice(0, nonNullBuffer.limit());
                if (i == firstChunkWithDataIndex) {
                    // writing starts from the first valid index in the first valid chunk
                    final int firstValidIndexInChunk = toIntExact(currentMinValidIndex % longsPerChunk);
                    buf.position(firstValidIndexInChunk * Long.BYTES);
                } else {
                    buf.position(0);
                }
                if (i == (totalNumOfChunks - 1)) {
                    // last array, so set limit to only the data needed
                    final long bytesWrittenSoFar = (long) memoryChunkSize * i;
                    final long remainingBytes = size() * Long.BYTES - bytesWrittenSoFar;
                    buf.limit(toIntExact(remainingBytes));
                } else {
                    buf.limit(buf.capacity());
                }
                MerkleDbFileUtils.completelyWrite(fc, buf);
            }
        } finally {
            // releasing memory allocated
            MemoryUtils.closeDirectByteBuffer(emptyBuffer);
        }
    }

    /**
     * Lookup a long in a data chunk.
     *
     * @param chunk     The data chunk
     * @param subIndex   The sub index of the long in that chunk
     * @return The stored long value at given index
     */
    @Override
    protected long lookupInChunk(@NonNull final ByteBuffer chunk, final long subIndex) {
        try {
            /* Do a volatile memory read from off-heap memory */
            return MemoryUtils.getLongVolatile(chunk, subIndex * Long.BYTES);
        } catch (final IndexOutOfBoundsException e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Index out of bounds in lookupInChunk: buf={}, offset={}, subIndex={}",
                    chunk,
                    subIndex,
                    e);
            throw e;
        }
    }

    protected ByteBuffer createChunk() {
        final ByteBuffer directBuffer = ByteBuffer.allocateDirect(memoryChunkSize);
        directBuffer.order(ByteOrder.nativeOrder());
        return directBuffer;
    }

    /**
     * Looks up a chunk by {@code chunkIndex} and, if the chunk exists,
     * zeros values up to {@code elementsToCleanUp} index.
     *
     * @param chunk            a chunk to clean up,
     * @param entriesToCleanUp number of elements to clean up starting with 0 index
     */
    @Override
    protected void partialChunkCleanup(
            @NonNull final ByteBuffer chunk, final boolean leftSide, final long entriesToCleanUp) {
        if (leftSide) {
            // cleans up all values up to newMinValidIndex in the first chunk
            MemoryUtils.setMemory(chunk, 0, entriesToCleanUp * Long.BYTES, (byte) 0);
        } else {
            // cleans up all values on the right side of the last chunk
            final long offset = (longsPerChunk - entriesToCleanUp) * Long.BYTES;
            MemoryUtils.setMemory(chunk, offset, entriesToCleanUp * Long.BYTES, (byte) 0);
        }
    }

    /**
     * Measures the amount of off-heap memory consumption.
     * It doesn't guarantee the exact result, there is a chance it may deviate
     * by a chunk size from the actual amount if this chunk was added or removed while the measurement.
     *
     * @return the amount of off-heap memory (in bytes) consumed by the list
     */
    @Override
    public long getOffHeapConsumption() {
        int nonEmptyChunkCount = 0;
        final int chunkListSize = chunkList.length();

        for (int i = 0; i < chunkListSize; i++) {
            if (chunkList.get(i) != null) {
                nonEmptyChunkCount++;
            }
        }

        return (long) nonEmptyChunkCount * memoryChunkSize;
    }
}
