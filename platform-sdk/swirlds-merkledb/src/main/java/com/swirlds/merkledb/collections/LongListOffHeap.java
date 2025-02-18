/*
 * Copyright (C) 2021-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.merkledb.collections;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNullElse;

import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.utilities.MemoryUtils;
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

/**
 * A {@link LongList} that stores its contents off-heap via a {@link AtomicReferenceArray} of direct
 * {@link ByteBuffer}s. Each {@link ByteBuffer} is the same size, so the "chunk" containing the
 * value for any given index is easily found using modular arithmetic. Note that <br>
 * to reduce memory consumption one can use {@link LongList#updateValidRange(long, long)}.
 * A call to this method discards memory chunks reserved for the indices that are before the index
 * passed as an argument subtracted by {@link AbstractLongList#reservedBufferLength}. The idea is to
 * keep the amount of memory defined by {@link AbstractLongList#reservedBufferLength} reserved even
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
     * Construct a new OffHeapLongList with the default 8Mb chunk size and 2Mb of reserved buffer
     */
    public LongListOffHeap() {
        this(DEFAULT_NUM_LONGS_PER_CHUNK, DEFAULT_MAX_LONGS_TO_STORE, DEFAULT_RESERVED_BUFFER_LENGTH);
    }

    /**
     * Construct a new LongListOffHeap with the specified chunk size
     *
     * @param numLongsPerChunk size for each chunk of memory to allocate. Max 16Gb = 16,384Mb
     * @param maxLongs the maximum number of longs permissible for this LongList
     * @param reservedBufferLength the number of indices before the minimal index to keep reserved
     */
    LongListOffHeap(final int numLongsPerChunk, final long maxLongs, final long reservedBufferLength) {
        super(numLongsPerChunk, maxLongs, reservedBufferLength);
    }

    /**
     * Construct a new OffHeapLongList with the default 8Mb chunk size and 2Mb of reserved buffer
     */
    public LongListOffHeap(final int reservedBufferLength) {
        this(DEFAULT_NUM_LONGS_PER_CHUNK, DEFAULT_MAX_LONGS_TO_STORE, reservedBufferLength);
    }

    /**
     * Create a {@link LongListOffHeap} from a file that was saved.
     *
     * @throws IOException If there was a problem reading the file
     */
    public LongListOffHeap(final Path file, final Configuration configuration) throws IOException {
        super(file, DEFAULT_RESERVED_BUFFER_LENGTH, configuration);
    }

    /** {@inheritDoc} */
    @Override
    protected ByteBuffer readChunkData(FileChannel fileChannel, int chunkIndex, int startIndex, int endIndex)
            throws IOException {
        ByteBuffer chunk = createChunk();
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
        final int firstChunkWithDataIndex = toIntExact(currentMinValidIndex / numLongsPerChunk);
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
                    final int firstValidIndexInChunk = toIntExact(currentMinValidIndex % numLongsPerChunk);
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
            final long offset = (numLongsPerChunk - entriesToCleanUp) * Long.BYTES;
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
