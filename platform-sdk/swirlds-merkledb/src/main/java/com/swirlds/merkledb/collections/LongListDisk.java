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
    private final Deque<Long> freeChunks;

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
     * Create a {@link LongListDisk} with default parameters.
     *
     * @param configuration platform configuration
     */
    public LongListDisk(final @NonNull Configuration configuration) {
        this(DEFAULT_NUM_LONGS_PER_CHUNK, DEFAULT_MAX_LONGS_TO_STORE, DEFAULT_RESERVED_BUFFER_LENGTH, configuration);
    }

    LongListDisk(
            final int numLongsPerChunk,
            final long maxLongs,
            final long reservedBufferLength,
            final @NonNull Configuration configuration) {
        super(numLongsPerChunk, maxLongs, reservedBufferLength);
        try {
            tempFile = createTempFile(DEFAULT_FILE_NAME, configuration);
            currentFileChannel = FileChannel.open(
                    tempFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        freeChunks = new ConcurrentLinkedDeque<>();
        fillBufferWithZeroes(initOrGetTransferBuffer());
    }

    /**
     * Create a {@link LongListDisk} on a file, if the file doesn't exist it will be created.
     *
     * @param file The file to read and write to
     * @param configuration platform configuration
     * @throws IOException If there was a problem reading the file
     */
    public LongListDisk(final Path file, final @NonNull Configuration configuration) throws IOException {
        this(file, DEFAULT_RESERVED_BUFFER_LENGTH, configuration);
    }

    LongListDisk(final Path path, final long reservedBufferLength, final @NonNull Configuration configuration)
            throws IOException {
        super(path, reservedBufferLength, configuration);
        freeChunks = new ConcurrentLinkedDeque<>();
        // IDE complains that the tempFile is not initialized, but it's initialized in readBodyFromFileChannelOnInit
        // which is called from the constructor of the parent class
        //noinspection ConstantValue
        if (tempFile == null) {
            throw new IllegalStateException("The temp file is not initialized");
        }
    }

    /**
     * Initializes the file channel to a temporary file.
     * @param path the path to the source file
     */
    @Override
    protected void onEmptyOrAbsentSourceFile(final Path path) throws IOException {
        tempFile = createTempFile(path.toFile().getName(), configuration);
    }

    /** {@inheritDoc} */
    @Override
    protected void readBodyFromFileChannelOnInit(final String sourceFileName, final FileChannel fileChannel)
            throws IOException {
        tempFile = createTempFile(sourceFileName, configuration);

        currentFileChannel = FileChannel.open(
                tempFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);

        super.readBodyFromFileChannelOnInit(sourceFileName, fileChannel);
    }

    /** {@inheritDoc} */
    @Override
    protected Long readChunkData(FileChannel fileChannel, int chunkIndex, int startIndex, int endIndex)
            throws IOException {
        // read from `fileChannel`
        final ByteBuffer transferBuffer = initOrGetTransferBuffer();
        fillBufferWithZeroes(transferBuffer);

        readDataIntoBuffer(fileChannel, chunkIndex, startIndex, endIndex, transferBuffer);

        final int firstChunkIndex = toIntExact(minValidIndex.get() / numLongsPerChunk);
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
        return (index % numLongsPerChunk) * Long.BYTES;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void writeLongsData(final FileChannel fc) throws IOException {
        final ByteBuffer transferBuffer = initOrGetTransferBuffer();
        final int totalNumOfChunks = calculateNumberOfChunks(size());
        final long currentMinValidIndex = minValidIndex.get();
        final int firstChunkWithDataIndex = toIntExact(currentMinValidIndex / numLongsPerChunk);

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
                    final int firstValidIndexInChunk = toIntExact(currentMinValidIndex % numLongsPerChunk);
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
                MerkleDbFileUtils.completelyRead(currentFileChannel, transferBuffer, chunkOffset);
                transferBuffer.position(currentPosition);
            } else {
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
            return maxOffset == -1 ? 0 : maxOffset + memoryChunkSize;
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
