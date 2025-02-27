// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static java.lang.Math.toIntExact;
import static java.nio.ByteBuffer.allocateDirect;

import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.utilities.MemoryUtils;
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

    /** Construct a new LongListHeap with the default number of longs per chunk. */
    public LongListHeap() {
        this(DEFAULT_NUM_LONGS_PER_CHUNK, DEFAULT_MAX_LONGS_TO_STORE, 0);
    }

    /**
     * Construct a new LongListHeap with the specified number of longs per chunk and default max
     * longs to store.
     */
    public LongListHeap(final int numLongsPerChunk) {
        this(numLongsPerChunk, Math.min(DEFAULT_MAX_LONGS_TO_STORE, (long) numLongsPerChunk * MAX_NUM_CHUNKS), 0);
    }

    /**
     * Construct a new LongListHeap with the specified number of longs per chunk and maximum number
     * of longs.
     *
     * @param numLongsPerChunk number of longs to store in each chunk of memory allocated
     * @param maxLongs the maximum number of longs permissible for this LongList
     */
    LongListHeap(final int numLongsPerChunk, final long maxLongs, final long reservedBufferLength) {
        super(numLongsPerChunk, maxLongs, reservedBufferLength);
    }

    /**
     * Create a {@link LongListHeap} from a file that was saved.
     *
     * @param file the file to read from
     * @throws IOException If there was a problem reading the file
     */
    public LongListHeap(final Path file, final Configuration configuration) throws IOException {
        super(file, 0, configuration);
    }

    /** {@inheritDoc} */
    @Override
    protected void readBodyFromFileChannelOnInit(final String sourceFileName, final FileChannel fileChannel)
            throws IOException {
        initReadBuffer = ByteBuffer.allocateDirect(memoryChunkSize).order(ByteOrder.nativeOrder());
        try {
            super.readBodyFromFileChannelOnInit(sourceFileName, fileChannel);
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
        return new AtomicLongArray(numLongsPerChunk);
    }
}
