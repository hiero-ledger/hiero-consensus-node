// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static com.swirlds.merkledb.utilities.HashTools.HASH_SIZE_BYTES;
import static com.swirlds.merkledb.utilities.HashTools.byteBufferToHash;
import static com.swirlds.merkledb.utilities.HashTools.hashToByteBuffer;
import static java.nio.ByteBuffer.allocate;
import static java.nio.ByteBuffer.allocateDirect;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.crypto.Hash;
import com.swirlds.merkledb.utilities.HashTools;
import com.swirlds.merkledb.utilities.MemoryUtils;
import com.swirlds.merkledb.utilities.MerkleDbFileUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An implementation of {@link HashList} which makes use of an expanding, dynamic list of {@link ByteBuffer}s
 * for storing hashes. An instance of this class should <strong>only</strong> be used for a homogenous set of hashes.
 * A hash normally serializes with both the hash bytes and a hash type. When scaled to billions of hashes,
 * this amounts to a lot of wasted space. This implementation assumes a homogenous set of hashes and omits serializing
 * the hash type, only storing the hash bytes themselves.
 *
 * <p>This class improves upon the memory usage of a simple hash array. In this class, each hash is stored as
 * exactly the number of hash bytes (48 for an SHA-384 hash). An array of hash objects would include java object
 * overhead, amounting to about a 2x overhead.
 *
 * <pre>
 * 32 bytes for object header + byte[] pointer + digest type pointer
 * + 16 bytes for byte[] object header + 4 bytes for byte[] length
 * + data size of 384 bits = 48 bytes.
 * = 100 bytes, or over 2x overhead.
 * </pre>
 */
public final class HashListByteBuffer implements HashList, OffHeapUser {
    /**
     * The version number for format of current data files
     */
    private static final int FILE_FORMAT_VERSION = 1;
    /**
     * The number of bytes to read for header
     */
    private static final int FILE_HEADER_SIZE =
            Integer.BYTES + Integer.BYTES + Long.BYTES + 1 + Long.BYTES + Long.BYTES + Integer.BYTES;
    /**
     * A suitable default value for the number of hashes to store per {@link ByteBuffer}.
     */
    private static final int DEFAULT_NUM_HASHES_PER_BUFFER = 1_000_000;

    /**
     * A suitable default value for the maximum number of hashes to store in a single instance
     * of {@link HashListByteBuffer}. This is to prevent a bug from creating off-heap stores that
     * are ridiculously large.
     */
    private static final long DEFAULT_MAX_HASHES_TO_STORE = 10_000_000_000L;

    /**
     * A copy-on-write list of buffers of data. Expands as needed to store buffers of hashes.
     */
    private final List<ByteBuffer> data = new CopyOnWriteArrayList<>();

    /**
     * The current maximum index that can be stored. This is determined dynamically based on the
     * actual indexes used. It will grow, but never shrinks. Ultimately it would be nice to have
     * some way to shrink this based on knowledge other parts of the system have about how many
     * hashes need to be stored, but in the real system, this isn't important because we don't
     * shrink the state size that dramatically, and on a reboot, this would be reset anyway.
     */
    private final AtomicLong maxIndexThatCanBeStored = new AtomicLong(-1);

    /**
     * The size of this HashList, ie number of hashes stored
     */
    private final AtomicLong numberOfHashesStored = new AtomicLong(0);

    /**
     * The number of hashes to store in each allocated buffer. Must be a positive integer.
     * If the value is small, then we will end up allocating a very large number of buffers.
     * If the value is large, then we will waste a lot of memory in the unfilled buffer.
     */
    private final int numHashesPerBuffer;

    /**
     * The amount of RAM needed to store one buffer of hashes. This will be computed based on the number of
     * bytes to store for the hash, and the {@link #numHashesPerBuffer}.
     */
    private final int memoryBufferSize;

    /**
     * The maximum number of hashes to be able to store in this data structure. This is used as a safety
     * measure to make sure no bug causes an out of memory issue by causing us to allocate more buffers
     * than the system can handle.
     */
    private final long maxHashes;

    /**
     * Whether to store the data on-heap or off-heap.
     */
    private final boolean offHeap;

    /**
     * Create a new off-heap {@link HashListByteBuffer} with default number of hashes per buffer and max capacity.
     */
    public HashListByteBuffer() {
        this(DEFAULT_NUM_HASHES_PER_BUFFER, DEFAULT_MAX_HASHES_TO_STORE, true);
    }

    /**
     * Create a {@link HashListByteBuffer} from a file that was saved.
     *
     * @throws IOException
     * 		If there was a problem reading the file
     */
    public HashListByteBuffer(Path file) throws IOException {
        try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
            // read header
            ByteBuffer headerBuffer = ByteBuffer.allocate(FILE_HEADER_SIZE);
            MerkleDbFileUtils.completelyRead(fc, headerBuffer);
            headerBuffer.rewind();
            final int formatVersion = headerBuffer.getInt();
            if (formatVersion != FILE_FORMAT_VERSION) {
                throw new IOException("Tried to read a file with incompatible file format version [" + formatVersion
                        + "], expected [" + FILE_FORMAT_VERSION + "].");
            }
            numHashesPerBuffer = headerBuffer.getInt();
            memoryBufferSize = numHashesPerBuffer * HASH_SIZE_BYTES;
            maxHashes = headerBuffer.getLong();
            offHeap = headerBuffer.get() == 1;
            maxIndexThatCanBeStored.set(headerBuffer.getLong());
            numberOfHashesStored.set(headerBuffer.getLong());
            final int numOfBuffers = headerBuffer.getInt();
            // read data
            for (int i = 0; i < numOfBuffers; i++) {
                ByteBuffer buffer = offHeap ? allocateDirect(memoryBufferSize) : allocate(memoryBufferSize);
                MerkleDbFileUtils.completelyRead(fc, buffer);
                buffer.position(0);
                data.add(buffer);
            }
        }
    }

    /**
     * Create a new {@link HashListByteBuffer}.
     *
     * @param numHashesPerBuffer
     * 		The number of hashes to store in each buffer. If the number of too large, then
     * 		the last buffer will have wasted space. If the number is too small, then an
     * 		excessive number of buffers will be created. Must be a positive number.
     * @param maxHashes
     * 		The maximum number of hashes to store in the data structure. Must be a non-negative integer
     * 		value.
     * @param offHeap
     * 		Whether to store the buffer off the Java heap.
     */
    public HashListByteBuffer(final int numHashesPerBuffer, final long maxHashes, final boolean offHeap) {
        if (numHashesPerBuffer < 1) {
            throw new IllegalArgumentException("The number of hashes per buffer must be positive");
        }

        if (maxHashes < 0) {
            throw new IllegalArgumentException("The maximum number of hashes must be non-negative");
        }

        this.numHashesPerBuffer = numHashesPerBuffer;
        this.memoryBufferSize = numHashesPerBuffer * HASH_SIZE_BYTES;
        this.maxHashes = maxHashes;
        this.offHeap = offHeap;
    }

    /**
     * Closes this HashList and wrapped HashList freeing any resources used
     */
    @Override
    public void close() throws IOException {
        maxIndexThatCanBeStored.set(0);
        numberOfHashesStored.set(0);
        if (offHeap) {
            for (final ByteBuffer directBuffer : data) {
                MemoryUtils.closeDirectByteBuffer(directBuffer);
            }
        }
        data.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash get(final long index) throws IOException {
        // Range-check on the index
        if (index < 0 || index >= maxHashes) {
            throw new IndexOutOfBoundsException();
        }

        // Note: if there is a race between the reader and a writer, such that the writer is
        // writing to a higher index than `maxIndexThatCanBeStored`, this is OK.
        if (index <= maxIndexThatCanBeStored.get()) {
            return byteBufferToHash(getBuffer(index), HashTools.getSerializationVersion());
        } else {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void put(final long index, final Hash hash) {
        // Range-check on the index
        if (index < 0 || index >= maxHashes) {
            throw new IndexOutOfBoundsException(
                    "Cannot put a hash at index " + index + " given " + maxHashes + " capacity");
        }

        // Expand data if needed
        maxIndexThatCanBeStored.updateAndGet(currentValue -> {
            while (index > currentValue) { // need to expand
                data.add(offHeap ? allocateDirect(memoryBufferSize) : allocate(memoryBufferSize));
                currentValue += numHashesPerBuffer;
            }
            return currentValue;
        });
        // update number of hashes stored
        numberOfHashesStored.updateAndGet(currentValue -> Math.max(currentValue, index + 1));
        // Get the right buffer
        hashToByteBuffer(hash, getBuffer(index));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long capacity() {
        return maxHashes;
    }

    /**
     * Get the number of hashes in this hash list.
     *
     * @return The size of the list. Will be non-negative.
     */
    @Override
    public long size() {
        return numberOfHashesStored.get();
    }

    /**
     * Get the maximum number of hashes this HashList can store, this is the maximum value capacity can grow to.
     *
     * @return maximum number of hashes this HashList can store
     */
    @Override
    public long maxHashes() {
        return maxHashes;
    }

    /**
     * Write all hashes in this HashList into a file
     *
     * @param file
     * 		The file to write into, it should not exist but its parent directory should exist and be writable.
     * @throws IOException
     * 		If there was a problem creating or writing to the file.
     */
    @Override
    public void writeToFile(Path file) throws IOException {
        final int numOfBuffers = data.size();
        try (final FileChannel fc = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            // write header
            ByteBuffer headerBuffer = ByteBuffer.allocate(FILE_HEADER_SIZE);
            headerBuffer.rewind();
            headerBuffer.putInt(FILE_FORMAT_VERSION);
            headerBuffer.putInt(numHashesPerBuffer);
            headerBuffer.putLong(maxHashes);
            headerBuffer.put((byte) (offHeap ? 1 : 0));
            headerBuffer.putLong(maxIndexThatCanBeStored.get());
            headerBuffer.putLong(numberOfHashesStored.get());
            headerBuffer.putInt(numOfBuffers);
            headerBuffer.flip();
            MerkleDbFileUtils.completelyWrite(fc, headerBuffer);
            // write data
            for (int i = 0; i < numOfBuffers; i++) {
                ByteBuffer buf = data.get(i).slice(); // slice so we don't mess with state of stored buffer
                buf.position(0);
                if (i == (numOfBuffers - 1)) {
                    // last array, so set limit to only the data needed
                    long bytesWrittenSoFar = (long) memoryBufferSize * (long) i;
                    long remainingBytes = (size() * HASH_SIZE_BYTES) - bytesWrittenSoFar;
                    buf.limit((int) remainingBytes);
                } else {
                    buf.limit(buf.capacity());
                }
                MerkleDbFileUtils.completelyWrite(fc, buf);
            }
        }
    }

    /**
     * Get off-heap usage of this hash list, in bytes. It's calculated as the number of
     * currently allocated buffers * number of hashes in each buffer * hash size. Even if
     * some buffers are not fully utilized, they still consume memory, this is why the
     * usage is based on the number of buffers rather than the number of stored hashes.
     *
     * <p>If this hash list is on-heap, this method returns zero.
     *
     * @return Off-heap usage in bytes, if this hash list is off-heap, or zero otherwise
     */
    @Override
    public long getOffHeapConsumption() {
        return offHeap ? (long) data.size() * numHashesPerBuffer * HASH_SIZE_BYTES : 0;
    }

    /**
     * Get the ByteBuffer for a given index. Assumes the buffer is already created.
     * For example, if the {@code index} is 13, and the {@link #numHashesPerBuffer} is 10,
     * then the 2nd buffer would be returned.
     *
     * @param index
     * 		the index we need the buffer for. This will never be out of range.
     * @return The ByteBuffer contain that index
     */
    private ByteBuffer getBuffer(final long index) {
        // This should never happen, because it is checked and validated by the callers to this method
        assert index >= 0 && index < numHashesPerBuffer * (long) data.size()
                : "The index " + index + " was out of range";

        // Figure out which buffer in `data` will contain the index
        final int bufferIndex = (int) (index / numHashesPerBuffer);
        // Create a new sub-buffer (slice). This is necessary for threading. In Java versions < 13, you must
        // have a unique buffer for each thread, because each buffer has its own position and limit state.
        // Once we have the buffer, compute the index within the buffer and the offset and then set the
        // position and limit appropriately.
        final ByteBuffer buffer = data.get(bufferIndex).slice(); // for threading
        final int subIndex = (int) (index % numHashesPerBuffer);
        final int offset = HASH_SIZE_BYTES * subIndex;
        buffer.position(offset);
        buffer.limit(offset + HASH_SIZE_BYTES);
        return buffer;
    }

    /**
     * toString for debugging
     */
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("num of buffers", data.size())
                .append("maxIndexThatCanBeStored", maxIndexThatCanBeStored)
                .toString();
    }
}
