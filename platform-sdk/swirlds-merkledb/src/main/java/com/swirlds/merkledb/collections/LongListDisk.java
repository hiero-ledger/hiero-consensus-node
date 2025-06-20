// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static java.lang.Math.toIntExact;
import static java.nio.file.Files.exists;
import static java.util.Objects.requireNonNull;

import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.utilities.MerkleDbFileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *  A direct on disk implementation of LongList. This implementation creates a temporary file to store the data.
 *  If the user provides a file, it will be used to create a copy of the in the temporary file. If the file is absent,
 *  it will take the name of the file provided by the user and create a temporary file with this name.
 * <p>
 *  Unlike the "snapshot" file, the temporary files doesn't contain the header, only the body.
 *
 */
public class LongListDisk extends AbstractLongList<Long> {

    private static final String STORE_POSTFIX = "longListDisk";
    private static final String DEFAULT_FILE_NAME = "LongListDisk.ll";
    /** A temp byte buffer for reading and writing longs */
    private static final ThreadLocal<ByteBuffer> TEMP_LONG_BUFFER_THREAD_LOCAL;

    /** This file channel is to work with the temporary file.
     */
    private FileChannel currentFileChannel;

    /**
     * Path to the temporary file used to store the data.
     * The field is effectively immutable, however it can't be declared
     * final because in some cases it has to be initialized in {@link LongListDisk#readBodyFromFileChannelOnInit}
     */
    private Path tempFile;

    /**
     * Path to the temp directory where tempFile above is located. Temp directories
     * are deleted automatically when the process exits. However, in case of long lists
     * on disk it makes sense to delete them explicitly when lists are closed, otherwise
     * there may be too many temp directories piled up.
     */
    private Path tempDir;

    /** A temp byte buffer for transferring data between file channels */
    private static final ThreadLocal<ByteBuffer> TRANSFER_BUFFER_THREAD_LOCAL;

    /**
     * Offsets of the chunks that are free to be used. The offsets are relative to the start of the file.
     */
    private final Deque<Long> freeChunks = new ConcurrentLinkedDeque<>();

    /**
     * A helper flag to make sure close() can be called multiple times.
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    static {
        TRANSFER_BUFFER_THREAD_LOCAL = new ThreadLocal<>();
        // it's initialized as 8 bytes (Long.BYTES) but likely it's going to be resized later
        TEMP_LONG_BUFFER_THREAD_LOCAL =
                ThreadLocal.withInitial(() -> ByteBuffer.allocate(Long.BYTES).order(ByteOrder.nativeOrder()));
    }

    /**
     * Create a new on-disk long list with the specified capacity. Number of longs per chunk and
     * reserved buffer size are read from the provided configuration.
     *
     * @param capacity Maximum number of longs permissible for this long list
     * @param configuration Platform configuration
     */
    public LongListDisk(final long capacity, final Configuration configuration) {
        super(capacity, configuration);
        initFileChannel(configuration);
        fillBufferWithZeroes(initOrGetTransferBuffer());
    }

    /**
     * Create a new on-disk long list with the specified chunk size, capacity, and reserved
     * buffer size.
     *
     * @param longsPerChunk Number of longs to store in each chunk of memory allocated
     * @param capacity Maximum number of longs permissible for this long list
     * @param reservedBufferSize Reserved buffer length that the list should have before
     *                           minimal index in the list
     */
    public LongListDisk(
            final int longsPerChunk,
            final long capacity,
            final long reservedBufferSize,
            final @NonNull Configuration configuration) {
        super(longsPerChunk, capacity, reservedBufferSize);
        initFileChannel(configuration);
        fillBufferWithZeroes(initOrGetTransferBuffer());
    }

    /**
     * Create a new on-disk long list from a file that was saved and the specified capacity. Number of
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
    public LongListDisk(@NonNull final Path file, final long capacity, @NonNull final Configuration configuration)
            throws IOException {
        super(file, capacity, configuration);
        if (tempFile == null) {
            throw new IllegalStateException("The temp file is not initialized");
        }
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
    public LongListDisk(
            @NonNull final Path path,
            final int longsPerChunk,
            final long capacity,
            final long reservedBufferSize,
            final @NonNull Configuration configuration)
            throws IOException {
        super(path, longsPerChunk, capacity, reservedBufferSize, configuration);
        // IDE complains that the tempFile is not initialized, but it's initialized in readBodyFromFileChannelOnInit
        // which is called from the constructor of the parent class
        if (tempFile == null) {
            throw new IllegalStateException("The temp file is not initialized");
        }
    }

    private void initFileChannel(final Configuration configuration) {
        if (tempFile != null) {
            throw new IllegalStateException("The temp file has been already initialized");
        }
        try {
            tempFile = createTempFile(DEFAULT_FILE_NAME, configuration);
            currentFileChannel = FileChannel.open(
                    tempFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void readBodyFromFileChannelOnInit(
            final String sourceFileName, final FileChannel fileChannel, final Configuration configuration)
            throws IOException {
        tempFile = createTempFile(sourceFileName, configuration);

        currentFileChannel = FileChannel.open(
                tempFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);

        super.readBodyFromFileChannelOnInit(sourceFileName, fileChannel, configuration);
    }

    /** {@inheritDoc} */
    @Override
    protected Long readChunkData(FileChannel fileChannel, int chunkIndex, int startIndex, int endIndex)
            throws IOException {
        // read from `fileChannel`
        final ByteBuffer transferBuffer = initOrGetTransferBuffer();
        fillBufferWithZeroes(transferBuffer);

        readDataIntoBuffer(fileChannel, chunkIndex, startIndex, endIndex, transferBuffer);

        final int firstChunkIndex = toIntExact(minValidIndex.get() / longsPerChunk);
        final long chunk = ((long) (chunkIndex - firstChunkIndex) * memoryChunkSize);

        // write to `currentFileChannel`
        int startOffset = startIndex * Long.BYTES;
        int endOffset = endIndex * Long.BYTES;

        transferBuffer.position(startOffset);
        transferBuffer.limit(endOffset);

        int bytesToWrite = endOffset - startOffset;
        long bytesWritten = MerkleDbFileUtils.completelyWrite(currentFileChannel, transferBuffer, chunk + startOffset);
        if (bytesWritten != bytesToWrite) {
            throw new IOException("Failed to write long list (disk) chunks, chunkIndex=" + chunkIndex + " expected="
                    + bytesToWrite + " actual=" + bytesWritten);
        }

        return chunk;
    }

    private void fillBufferWithZeroes(ByteBuffer transferBuffer) {
        Arrays.fill(transferBuffer.array(), (byte) IMPERMISSIBLE_VALUE);
        transferBuffer.position(0);
        transferBuffer.limit(memoryChunkSize);
    }

    private ByteBuffer initOrGetTransferBuffer() {
        ByteBuffer buffer = TRANSFER_BUFFER_THREAD_LOCAL.get();
        if ((buffer == null) || (buffer.capacity() < memoryChunkSize)) {
            buffer = ByteBuffer.allocate(memoryChunkSize).order(ByteOrder.nativeOrder());
            TRANSFER_BUFFER_THREAD_LOCAL.set(buffer);
        } else {
            // clean up the buffer
            buffer.clear();
        }
        buffer.limit(memoryChunkSize);
        return buffer;
    }

    Path createTempFile(final String sourceFileName, final @NonNull Configuration configuration) throws IOException {
        requireNonNull(configuration);
        // FileSystemManager.create() deletes the temp directory created previously. It means,
        // every new LongListDisk instance erases the folder used by the previous LongListDisk, if any!
        // final Path directory = FileSystemManager.create(configuration).resolveNewTemp(STORE_POSTFIX);
        tempDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory(STORE_POSTFIX, configuration);
        if (!exists(tempDir)) {
            Files.createDirectories(tempDir);
        }
        return tempDir.resolve(sourceFileName);
    }

    /** {@inheritDoc} */
    @Override
    protected synchronized void putToChunk(final Long chunk, final int subIndex, final long value) {
        try {
            final ByteBuffer buf = TEMP_LONG_BUFFER_THREAD_LOCAL.get();
            final long offset = chunk + (long) subIndex * Long.BYTES;
            // write new value to file
            buf.putLong(0, value);
            buf.position(0);
            MerkleDbFileUtils.completelyWrite(currentFileChannel, buf, offset);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    protected synchronized boolean putIfEqual(
            final Long chunk, final int subIndex, final long oldValue, long newValue) {
        final ByteBuffer buf = TEMP_LONG_BUFFER_THREAD_LOCAL.get();
        buf.position(0);
        try {
            final long offset = chunk + (long) subIndex * Long.BYTES;
            MerkleDbFileUtils.completelyRead(currentFileChannel, buf, offset);
            final long filesOldValue = buf.getLong(0);
            if (filesOldValue == oldValue) {
                // write new value to file
                buf.putLong(0, newValue);
                buf.position(0);
                MerkleDbFileUtils.completelyWrite(currentFileChannel, buf, offset);
                return true;
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return false;
    }

    /**
     * Calculate the offset in the chunk for the given index.
     * @param index the index to use
     * @return the offset in the chunk for the given index
     */
    private long calculateOffsetInChunk(long index) {
        return (index % longsPerChunk) * Long.BYTES;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeLongsData(final FileChannel fc) throws IOException {
        final ByteBuffer transferBuffer = initOrGetTransferBuffer();
        final int totalNumOfChunks = calculateNumberOfChunks(size());
        final long currentMinValidIndex = minValidIndex.get();
        final int firstChunkWithDataIndex = toIntExact(currentMinValidIndex / longsPerChunk);

        // The following logic sequentially processes chunks. This kind of processing allows to get rid of
        // non-contiguous memory allocation and gaps that may be present in the current file.
        // MerkleDbFileUtils.completelyTransferFrom would work faster, it wouldn't allow
        // the required rearrangement of data.
        for (int i = firstChunkWithDataIndex; i < totalNumOfChunks; i++) {
            Long currentChunkStartOffset = chunkList.get(i);
            // if the chunk is null, we write zeroes to the file. If not, we write the data from the chunk
            if (currentChunkStartOffset != null) {
                final long chunkOffset;
                if (i == firstChunkWithDataIndex) {
                    // writing starts from the first valid index in the first valid chunk
                    final int firstValidIndexInChunk = toIntExact(currentMinValidIndex % longsPerChunk);
                    transferBuffer.position(firstValidIndexInChunk * Long.BYTES);
                    chunkOffset = currentChunkStartOffset + calculateOffsetInChunk(currentMinValidIndex);
                } else {
                    // writing the whole chunk
                    transferBuffer.position(0);
                    chunkOffset = currentChunkStartOffset;
                }
                if (i == (totalNumOfChunks - 1)) {
                    // the last array, so set limit to only the data needed
                    final long bytesWrittenSoFar = (long) memoryChunkSize * i;
                    final long remainingBytes = (size() * Long.BYTES) - bytesWrittenSoFar;
                    transferBuffer.limit(toIntExact(remainingBytes));
                } else {
                    transferBuffer.limit(memoryChunkSize);
                }
                int currentPosition = transferBuffer.position();
                final int toRead = transferBuffer.remaining();
                final int read = MerkleDbFileUtils.completelyRead(currentFileChannel, transferBuffer, chunkOffset);
                if (toRead != read) {
                    throw new IOException("Failed to read a chunk from the file, offset=" + chunkOffset + ", toRead="
                            + toRead + ", read=" + read + ", file size=" + currentFileChannel.size());
                }
                // Restore the position, so the right part of transferBuffer is written to the target
                // file channel below. No need to restore the limit, it isn't changed by completelyRead()
                transferBuffer.position(currentPosition);
            } else {
                // fillBufferWithZeroes() takes care of buffer position and limit
                fillBufferWithZeroes(transferBuffer);
            }

            MerkleDbFileUtils.completelyWrite(fc, transferBuffer);
        }
    }

    /**
     * Lookup a long in data
     *
     * @param chunkOffset the index of the chunk the long is contained in
     * @param subIndex   The sub index of the long in that chunk
     * @return The stored long value at given index
     */
    @Override
    protected long lookupInChunk(@NonNull final Long chunkOffset, final long subIndex) {
        try {
            final ByteBuffer buf = TEMP_LONG_BUFFER_THREAD_LOCAL.get();
            // if there is nothing to read the buffer will have the default value
            buf.putLong(0, IMPERMISSIBLE_VALUE);
            buf.clear();
            final long offset = chunkOffset + subIndex * Long.BYTES;
            MerkleDbFileUtils.completelyRead(currentFileChannel, buf, offset);
            return buf.getLong(0);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     *  Flushes and closes the file chanel and clears the free chunks offset list.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            // Already closed
            return;
        }
        try {
            // flush
            if (currentFileChannel.isOpen()) {
                currentFileChannel.force(false);
            }
            // release all chunks
            super.close();
            // now close
            currentFileChannel.close();
            freeChunks.clear();
            Files.delete(tempFile);
            // The directory must be empty at this point
            Files.delete(tempDir);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void closeChunk(@NonNull final Long chunk) {
        final ByteBuffer transferBuffer = initOrGetTransferBuffer();
        fillBufferWithZeroes(transferBuffer);
        try {
            currentFileChannel.write(transferBuffer, chunk);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        freeChunks.add(chunk);
    }

    /** {@inheritDoc} */
    @Override
    protected void partialChunkCleanup(
            @NonNull final Long chunkOffset, final boolean leftSide, final long entriesToCleanUp) {
        final ByteBuffer transferBuffer = initOrGetTransferBuffer();
        fillBufferWithZeroes(transferBuffer);
        transferBuffer.limit(toIntExact(entriesToCleanUp * Long.BYTES));
        if (leftSide) {
            try {
                currentFileChannel.write(transferBuffer, chunkOffset);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            long cleanUpOffset = memoryChunkSize - (entriesToCleanUp * Long.BYTES);
            try {
                currentFileChannel.write(transferBuffer, chunkOffset + cleanUpOffset);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected Long createChunk() {
        Long chunkOffset = freeChunks.poll();
        if (chunkOffset == null) {
            long maxOffset = -1;
            for (int i = 0; i < chunkList.length(); i++) {
                Long currentOffset = chunkList.get(i);
                maxOffset = Math.max(maxOffset, currentOffset == null ? -1 : currentOffset);
            }
            final long chunk = maxOffset == -1 ? 0 : maxOffset + memoryChunkSize;
            try {
                // Append the full chunk to the end of the backing file
                final ByteBuffer tmp = initOrGetTransferBuffer();
                fillBufferWithZeroes(tmp);
                MerkleDbFileUtils.completelyWrite(currentFileChannel, tmp, chunk);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            return chunk;
        } else {
            return chunkOffset;
        }
    }

    // exposed for test purposes only - DO NOT USE IN PROD CODE
    FileChannel getCurrentFileChannel() {
        return currentFileChannel;
    }

    // exposed for test purposes only - DO NOT USE IN PROD CODE
    LongListDisk resetTransferBuffer() {
        TRANSFER_BUFFER_THREAD_LOCAL.remove();
        return this;
    }
}
