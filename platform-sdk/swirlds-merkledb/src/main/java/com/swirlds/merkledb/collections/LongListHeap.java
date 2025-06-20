// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static java.lang.Math.toIntExact;
import static java.nio.ByteBuffer.allocateDirect;

import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.utilities.MerkleDbFileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLongArray;
import org.hiero.base.utility.MemoryUtils;

/**
 * A {@link LongList} that stores its contents on-heap via a {@link CopyOnWriteArrayList} of {@link
 * AtomicLongArray}s. Each {@link AtomicLongArray} is the same size, so the "chunk" containing the
 * value for any given index is easily found using modular arithmetic.
 *
 * <p>It is important to note that if indexes are not used sequentially from zero, many (or most) of
 * the chunks in the list may consume RAM without storing any longs. So this data structure is only
 * appropriate for use cases where list indices are filled in roughly ascending order, starting from
 * zero.
 *
 * <p>Per the {@link LongList} contract, this class is thread-safe for both concurrent reads and
 * writes.
 *
 * <p>Some others have tried similar but different ideas ( <a
 * href="https://philosopherdeveloper.com/posts/how-to-build-a-thread-safe-lock-free-resizable-array.html">see
 * here for example</a>).
 */
@SuppressWarnings("unused")
public final class LongListHeap extends AbstractLongList<AtomicLongArray> {

    /** A buffer for reading chunk data from the file only during the initialization. */
    private ByteBuffer initReadBuffer;

    /**
     * Create a new on-heap long list with the specified capacity. Number of longs per chunk and
     * reserved buffer size are read from the provided configuration.
     *
     * @param capacity Maximum number of longs permissible for this long list
     * @param configuration Platform configuration
     */
    public LongListHeap(final long capacity, final Configuration configuration) {
        super(capacity, configuration);
    }

    /**
     * Create a new on-heap long list with the specified chunk size, capacity, and reserved
     * buffer size.
     *
     * @param longsPerChunk Number of longs to store in each chunk of memory allocated
     * @param capacity Maximum number of longs permissible for this long list
     * @param reservedBufferSize Reserved buffer length that the list should have before
     *                           minimal index in the list
     */
    public LongListHeap(final int longsPerChunk, final long capacity, final long reservedBufferSize) {
        super(longsPerChunk, capacity, reservedBufferSize);
    }

    /**
     * Create a new on-heap long list from a file that was saved and the specified capacity. Number of
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
    public LongListHeap(@NonNull final Path file, final long capacity, @NonNull final Configuration configuration)
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
    public LongListHeap(
            @NonNull final Path path,
            final int longsPerChunk,
            final long capacity,
            final long reservedBufferSize,
            final Configuration configuration)
            throws IOException {
        super(path, longsPerChunk, capacity, reservedBufferSize, configuration);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void readBodyFromFileChannelOnInit(
            final String sourceFileName, final FileChannel fileChannel, Configuration configuration)
            throws IOException {
        initReadBuffer = ByteBuffer.allocateDirect(memoryChunkSize).order(ByteOrder.nativeOrder());
        try {
            super.readBodyFromFileChannelOnInit(sourceFileName, fileChannel, configuration);
        } finally {
            MemoryUtils.closeDirectByteBuffer(initReadBuffer);
        }
    }

    /** {@inheritDoc} */
    @Override
    protected AtomicLongArray readChunkData(FileChannel fileChannel, int chunkIndex, int startIndex, int endIndex)
            throws IOException {
        AtomicLongArray chunk = createChunk();

        readDataIntoBuffer(fileChannel, chunkIndex, startIndex, endIndex, initReadBuffer);

        final int startOffset = startIndex * Long.BYTES;
        final int endOffset = endIndex * Long.BYTES;
        initReadBuffer.position(startOffset);
        initReadBuffer.limit(endOffset);

        while (initReadBuffer.hasRemaining()) {
            int index = initReadBuffer.position() / Long.BYTES;
            chunk.set(index, initReadBuffer.getLong());
        }

        return chunk;
    }

    /** {@inheritDoc} */
    @Override
    protected void putToChunk(AtomicLongArray chunk, int subIndex, long value) {
        chunk.set(subIndex, value);
    }

    /** {@inheritDoc} */
    @Override
    protected boolean putIfEqual(AtomicLongArray chunk, int subIndex, long oldValue, long newValue) {
        return chunk.compareAndSet(subIndex, oldValue, newValue);
    }

    /**
     * Write the long data to file, This it is expected to be in one simple block of raw longs.
     *
     * @param fc The file channel to write to
     * @throws IOException if there was a problem writing longs
     */
    @Override
    protected void writeLongsData(final FileChannel fc) throws IOException {
        // write data
        final ByteBuffer tempBuffer = allocateDirect(1024 * 1024);
        tempBuffer.order(ByteOrder.nativeOrder());
        final LongBuffer tempLongBuffer = tempBuffer.asLongBuffer();
        for (long i = minValidIndex.get(); i < size(); i++) {
            // if buffer is full then write
            if (!tempLongBuffer.hasRemaining()) {
                tempBuffer.clear();
                MerkleDbFileUtils.completelyWrite(fc, tempBuffer);
                tempLongBuffer.clear();
            }
            // add value to buffer
            tempLongBuffer.put(get(i, 0));
        }
        // write any remaining
        if (tempLongBuffer.position() > 0) {
            tempBuffer.position(0);
            tempBuffer.limit(tempLongBuffer.position() * Long.BYTES);
            MerkleDbFileUtils.completelyWrite(fc, tempBuffer);
        }
    }

    /**
     * Lookup a long in data
     *
     * @param chunk the chunk the long is contained in
     * @param subIndex  The sub index of the long in that chunk
     * @return The stored long value at given index
     */
    @Override
    protected long lookupInChunk(@NonNull final AtomicLongArray chunk, final long subIndex) {
        return chunk.get(toIntExact(subIndex));
    }

    /** {@inheritDoc} */
    @Override
    protected void partialChunkCleanup(
            @NonNull final AtomicLongArray atomicLongArray, final boolean leftSide, final long entriesToCleanUp) {
        if (leftSide) {
            for (int i = 0; i < entriesToCleanUp; i++) {
                atomicLongArray.set(i, IMPERMISSIBLE_VALUE);
            }
        } else {
            for (int i = toIntExact(atomicLongArray.length() - entriesToCleanUp); i < atomicLongArray.length(); i++) {
                atomicLongArray.set(i, IMPERMISSIBLE_VALUE);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected AtomicLongArray createChunk() {
        return new AtomicLongArray(longsPerChunk);
    }
}
