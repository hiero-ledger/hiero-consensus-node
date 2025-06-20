// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static com.swirlds.merkledb.utilities.HashTools.HASH_SIZE_BYTES;
import static com.swirlds.merkledb.utilities.HashTools.byteBufferToHash;
import static com.swirlds.merkledb.utilities.HashTools.hashToByteBuffer;
import static java.nio.ByteBuffer.allocate;
import static java.nio.ByteBuffer.allocateDirect;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.utilities.HashTools;
import com.swirlds.merkledb.utilities.MerkleDbFileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.base.crypto.Hash;
import org.hiero.base.utility.MemoryUtils;

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
     * File format version 1. File structure: version (int), hashes per buffer (int),
     * max hashes (long), off/on-heap (byte), max index (long), num hashes (long),
     * num buffers (int).
     */
    static final int FILE_FORMAT_VERSION_V1 = 1;

    /**
     * File format version 2. File structure: version (int), size (aka num hashes, long),
     * capacity (max size, long).
     */
    static final int FILE_FORMAT_VERSION_V2 = 2;

    /**
     * Number of bytes in header v1. The header doesn't include the version number.
     */
    static final int FILE_HEADER_SIZE_V1 = Integer.BYTES // hashes per buffer
            + Long.BYTES // max hashes
            + 1 // off/on heap
            + Long.BYTES // max index
            + Long.BYTES // num hashes
            + Integer.BYTES; // num buffers

    /**
     * Number of bytes in header v2. The header doesn't include the version number.
     */
    static final int FILE_HEADER_SIZE_V2 = Long.BYTES // size (num hashes)
            + Long.BYTES; // capacity

    /**
     * A copy-on-write list of buffers of data. Expands as needed to store buffers of hashes.
     * All buffers stored in this list have position 0 and limit == memoryBufferSize.
     */
    private final List<ByteBuffer> data = new CopyOnWriteArrayList<>();

    /**
     * The maximum number of hashes to be able to store in this data structure. This is used as a safety
     * measure to make sure no bug causes an out of memory issue by causing us to allocate more buffers
     * than the system can handle.
     */
    private final long capacity;

    /**
     * The number of hashes stored in this hash list.
     */
    private final AtomicLong size = new AtomicLong(0);

    /**
     * The number of hashes to store in each allocated buffer. Must be a positive integer.
     * If the value is small, then we will end up allocating a very large number of buffers.
     * If the value is large, then we will waste a lot of memory in the unfilled buffer.
     */
    private final int hashesPerBuffer;

    /**
     * The amount of RAM needed to store one buffer of hashes. This will be computed based on the number of
     * bytes to store for the hash, and the {@link #hashesPerBuffer}.
     */
    private final int memoryBufferSize;

    /**
     * Whether to store the data on-heap or off-heap.
     */
    private final boolean offHeap;

    /**
     * Create a {@link HashListByteBuffer} from a file that was saved.
     *
     * @param file The file to load the hash list from
     * @param capacity The max number of hashes to store in this hash list. Must be non-negative
     * @param configuration Platform configuration
     * @throws IOException If the file doesn't exist or there was a problem reading the file
     */
    public HashListByteBuffer(@NonNull final Path file, final long capacity, @NonNull final Configuration configuration)
            throws IOException {
        requireNonNull(file);
        requireNonNull(configuration);
        final MerkleDbConfig merkleDbConfig = configuration.getConfigData(MerkleDbConfig.class);
        if (!Files.exists(file)) {
            throw new IOException("Cannot load hash list, file doesn't exist: " + file.toAbsolutePath());
        }
        try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
            final ByteBuffer versionBuffer = ByteBuffer.allocate(Integer.BYTES);
            if (MerkleDbFileUtils.completelyRead(fc, versionBuffer) != Integer.BYTES) {
                throw new IOException("Failed to read hash list file version");
            }
            final int formatVersion = versionBuffer.getInt(0);
            // Always use the provided capacity. If loaded from V1 file, the value is checked against
            // numHashes from V1 header. If loaded from V2 file, the value is checked against capacity
            // from V2 header
            this.capacity = capacity;
            this.hashesPerBuffer = merkleDbConfig.hashStoreRamBufferSize();
            this.memoryBufferSize = hashesPerBuffer * HASH_SIZE_BYTES;
            this.offHeap = merkleDbConfig.hashStoreRamOffHeapBuffers();
            if (formatVersion == FILE_FORMAT_VERSION_V1) {
                final ByteBuffer headerBuffer = ByteBuffer.allocate(FILE_HEADER_SIZE_V1);
                MerkleDbFileUtils.completelyRead(fc, headerBuffer);
                headerBuffer.flip();
                // hashesPerBuffer from file is ignored, initialized from the config instead
                headerBuffer.getInt();
                // capacity from file is ignored, the provided capacity is used instead
                headerBuffer.getLong();
                // offHeap from file is ignored, initialized from the config instead
                headerBuffer.get();
                // maxIndexThatCanBeStored from file is ignored, initialized from the config instead
                headerBuffer.getLong();
                size.set(headerBuffer.getLong());
                if (size.get() > capacity) {
                    throw new IllegalArgumentException(
                            "Hash list in the file is too large, size=" + size.get() + ", capacity=" + capacity);
                }
                // numOfBuffers from file is ignored, initialized from capacity + hashesPerBuffer
                headerBuffer.getInt();
            } else if (formatVersion == FILE_FORMAT_VERSION_V2) {
                final ByteBuffer headerBuffer = ByteBuffer.allocate(FILE_HEADER_SIZE_V2);
                if (MerkleDbFileUtils.completelyRead(fc, headerBuffer) != FILE_HEADER_SIZE_V2) {
                    throw new IOException("Failed to read hash list file header");
                }
                headerBuffer.clear();
                size.set(headerBuffer.getLong());
                final long capacityFromFile = headerBuffer.getLong();
                if (capacityFromFile != capacity) {
                    throw new IllegalArgumentException(
                            "Hash list capacity mismatch, expected=" + capacity + ", loaded=" + capacityFromFile);
                }
            } else {
                throw new UnsupportedOperationException(
                        "Hash list file version " + formatVersion + " is not supported");
            }
            int numOfBuffers = Math.toIntExact(size.get() / hashesPerBuffer);
            if (size.get() % hashesPerBuffer != 0) {
                numOfBuffers++;
            }
            // read data
            for (int i = 0; i < numOfBuffers; i++) {
                final ByteBuffer buffer = offHeap ? allocateDirect(memoryBufferSize) : allocate(memoryBufferSize);
                buffer.position(0);
                int toRead = memoryBufferSize;
                // The last buffer may not be read in full
                if ((i == numOfBuffers - 1) && (size.get() % hashesPerBuffer != 0)) {
                    toRead = Math.toIntExact(size.get() % hashesPerBuffer * HASH_SIZE_BYTES);
                }
                buffer.limit(toRead);
                final int read = MerkleDbFileUtils.completelyRead(fc, buffer);
                if (read != toRead) {
                    throw new IOException("Failed to read hashes, buffer=" + i + " toRead=" + toRead + " read=" + read);
                }
                // Buffers are always stored with position=0 and limit=memoryBufferSize, even if
                // this is the last buffer with size() in the middle of it
                buffer.clear();
                data.add(buffer);
            }
        }
    }

    /**
     * Create a new {@link HashListByteBuffer}. Number of hashes per buffer and on/off-heap flag
     * are read from the provided platform configuration.
     *
     * @param capacity The number of hashes to store in this hash list. Must be non-negative
     * @param configuration Platform configuration
     */
    public HashListByteBuffer(final long capacity, @NonNull final Configuration configuration) {
        requireNonNull(configuration);
        final MerkleDbConfig merkleDbConfig = configuration.getConfigData(MerkleDbConfig.class);
        if (capacity < 0) {
            throw new IllegalArgumentException("The maximum number of hashes must be non-negative");
        }
        this.capacity = capacity;
        this.hashesPerBuffer = merkleDbConfig.hashStoreRamBufferSize();
        this.memoryBufferSize = hashesPerBuffer * HASH_SIZE_BYTES;
        this.offHeap = merkleDbConfig.hashStoreRamOffHeapBuffers();
    }

    /**
     * Closes this HashList and wrapped HashList freeing any resources used
     */
    @Override
    public void close() {
        size.set(0);
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
        if (index < 0 || index >= capacity) {
            throw new IndexOutOfBoundsException();
        }

        // Note: if there is a race between the reader and a writer, such that the writer is
        // writing to a higher index than `size`, this is OK
        if (index < size.get()) {
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
        if (index < 0 || index >= capacity) {
            throw new IndexOutOfBoundsException(
                    "Cannot put a hash at index " + index + " given " + capacity + " capacity");
        }

        // Expand data if needed
        long currentMaxIndex = (long) data.size() * hashesPerBuffer - 1;
        if (currentMaxIndex < index) {
            synchronized (this) {
                currentMaxIndex = (long) data.size() * hashesPerBuffer - 1;
                while (currentMaxIndex < index) { // need to expand
                    data.add(offHeap ? allocateDirect(memoryBufferSize) : allocate(memoryBufferSize));
                    currentMaxIndex += hashesPerBuffer;
                }
            }
        }
        // update number of hashes stored
        size.updateAndGet(currentValue -> Math.max(currentValue, index + 1));
        // Get the right buffer
        hashToByteBuffer(hash, getBuffer(index));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long capacity() {
        return capacity;
    }

    /**
     * Get the number of hashes in this hash list.
     *
     * @return The size of the list. Will be non-negative.
     */
    @Override
    public long size() {
        return size.get();
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
            final ByteBuffer headerBuffer = ByteBuffer.allocate(Integer.BYTES + FILE_HEADER_SIZE_V2);
            headerBuffer.putInt(FILE_FORMAT_VERSION_V2);
            headerBuffer.putLong(size.get());
            headerBuffer.putLong(capacity);
            headerBuffer.flip();
            assert headerBuffer.remaining() == Integer.BYTES + FILE_HEADER_SIZE_V2;
            if (MerkleDbFileUtils.completelyWrite(fc, headerBuffer) != headerBuffer.limit()) {
                throw new IOException("Failed to write hash list header to file");
            }
            // write data
            for (int i = 0; i < numOfBuffers; i++) {
                final ByteBuffer dataBuffer = data.get(i).slice(); // slice so we don't mess with state of stored buffer
                dataBuffer.position(0);
                if (i == (numOfBuffers - 1)) {
                    // last array, so set limit to only the data needed
                    final long bytesWrittenSoFar = (long) memoryBufferSize * i;
                    final int remainingBytes = Math.toIntExact(size() * HASH_SIZE_BYTES - bytesWrittenSoFar);
                    dataBuffer.limit(remainingBytes);
                } else {
                    dataBuffer.limit(memoryBufferSize);
                }
                final int toWrite = dataBuffer.limit();
                final int written = MerkleDbFileUtils.completelyWrite(fc, dataBuffer);
                if (written != toWrite) {
                    throw new IOException("Failed to write hash list data buffer to file");
                }
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
        return offHeap ? (long) data.size() * hashesPerBuffer * HASH_SIZE_BYTES : 0;
    }

    /**
     * Get the ByteBuffer for a given index. Assumes the buffer is already created.
     * For example, if the {@code index} is 13, and the {@link #hashesPerBuffer} is 10,
     * then the 2nd buffer would be returned.
     *
     * @param index
     * 		the index we need the buffer for. This will never be out of range.
     * @return The ByteBuffer contain that index
     */
    private ByteBuffer getBuffer(final long index) {
        // This should never happen, because it is checked and validated by the callers to this method
        assert index >= 0 && index < hashesPerBuffer * (long) data.size() : "The index " + index + " was out of range";

        // Figure out which buffer in `data` will contain the index
        final int bufferIndex = Math.toIntExact(index / hashesPerBuffer);
        // Create a new sub-buffer (slice). This is necessary for threading. In Java versions < 13, you must
        // have a unique buffer for each thread, because each buffer has its own position and limit state.
        // Once we have the buffer, compute the index within the buffer and the offset and then set the
        // position and limit appropriately.
        final ByteBuffer buffer = data.get(bufferIndex).slice(); // for threading
        final int subIndex = Math.toIntExact(index % hashesPerBuffer);
        final int offset = HASH_SIZE_BYTES * subIndex;
        buffer.position(offset);
        buffer.limit(offset + HASH_SIZE_BYTES);
        return buffer;
    }

    // For testing purposes. Not thread safe, don't use it in parallel with put()
    int getCurrentBufferCount() {
        return data.size();
    }

    /**
     * toString for debugging
     */
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("size", size.get())
                .append("capacity", capacity)
                .append("hashesPerBuffer", hashesPerBuffer)
                .append("num of buffers", data.size())
                .toString();
    }
}
