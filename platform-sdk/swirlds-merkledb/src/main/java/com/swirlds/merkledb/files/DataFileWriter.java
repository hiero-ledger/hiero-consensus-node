// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.base.units.UnitConstants.KIBIBYTES_TO_BYTES;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_ITEMS;
import static com.swirlds.merkledb.files.DataFileCommon.PAGE_SIZE;
import static com.swirlds.merkledb.files.DataFileCommon.createDataFilePath;

import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.MemoryData;
import com.swirlds.merkledb.utilities.MerkleDbFileUtils;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

/**
 * Class for creating and sequentially writing to the file. A data file contains a header
 * containing {@link DataFileMetadata} followed by data items. Each data item is considered
 * as a black box.
 *
 * <p>{@link #close()} must be called after done writing data using {@link #storeDataItem(BufferedData)},
 * or {@link #storeDataItem(Consumer, int)}, or {@link #storeDataItemWithTag(BufferedData)}
 * any number of times. When a file writer is closed, the file size is set to the last byte of
 * the last data item written.
 *
 * <p>Right after a data file writer is created, it's ready to write from multiple threads.
 * After all write calls are finished, the writer should be closed using {@link #close()}. It
 * is callers' responsibility to make sure there are no write calls in progress, when writers
 * are closed.
 *
 * <p>Internally, writing is implemented as mapping parts of the file (called writing windows)
 * and writing to them. Every call to store a data item increases the current writing offset in
 * the file atomically. For this offset, the right writing window is identified and mapped to
 * memory, if needed. After the data item is written, the window is notified that the right
 * number of bytes have been written to it. Once the number of bytes written is equal to the
 * size of a writing window, the window is closed, and the corresponding memory segment is
 * unmapped.
 *
 * <p>Mapped memory segments are overlapping, this is done to avoid writing a single data item
 * to multiple mapped segments. If data buffer size is 100Mb, then the first window is mapped
 * at offset 0 and length 100Mb, the second window is mapped at offset 50Mb and length 100Mb,
 * and so on. Once 50Mb of data is written to a window, it gets unmapped.
 *
 * <p>{@link DataFileReader} or {@link DataFileIterator} can be used to read file back and access data items.
 */
public final class DataFileWriter {

    /**
     * Default max file size, in bytes.
     */
    private static final long DEFAULT_MAX_FILE_SIZE = 1L << 40;

    /**
     * Default buffer size for writing, in bytes.
     */
    private static final int DEFAULT_BUF_SIZE = PAGE_SIZE * KIBIBYTES_TO_BYTES * 16;

    private static final String ERROR_DATA_ITEM_TOO_LARGE = "Data item is too large to write to a data file";

    // These two thread locals are a workaround for missing PBJ API to write to MemoryData
    // objects at specified offsets (positioned writes). Once this API is available, data
    // file writers will write directly into MemoryData (wrapped over mapped memory segments).
    // See https://github.com/hashgraph/pbj/issues/790 for details
    private static final ThreadLocal<MemorySegment> SEGMENT_CACHE = new ThreadLocal<>();
    private static final ThreadLocal<MemoryData> WRITE_CACHE = new ThreadLocal<>();

    /** The path to the data file we are writing */
    private final Path path;

    private final FileChannel fileChannel;

    /** File metadata */
    private DataFileMetadata metadata;

    /** Total number of items written to this file */
    private final AtomicLong itemsCount = new AtomicLong(0);

    /**
     * Offset, in bytes, of the current position in the file channel, where the next
     * data item will be written.
     */
    private final AtomicLong currentWriteOffset = new AtomicLong(0);

    /**
     * Writing windows. Each window has an offset in the file, a mapped memory segment
     * used to write into the window, and an arena to release (unmap) the memory
     * segment, when the window is completely written.
     */
    private final AtomicReferenceArray<WritingWindow> writingWindows;

    /**
     * Data buffer size is the size of single writing window and the max data item size to
     * write. Note that writing windows overlap with each other, so mapped memory segments
     * are two times larger than this buffer size.
     */
    private final int dataBufferSize;

    /**
     * Indicates if this file writer has been closed. Only set and accessed on the
     * writing thread.
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Create a new data file with moving mapped byte buffer of 256Mb size.
     */
    public DataFileWriter(
            final String filePrefix,
            final Path dataFileDir,
            final int index,
            final Instant creationTime,
            final int compactionLevel)
            throws IOException {
        this(filePrefix, dataFileDir, index, creationTime, compactionLevel, DEFAULT_BUF_SIZE, DEFAULT_MAX_FILE_SIZE);
    }

    /**
     * Create a new data file in the given directory, in append mode. Puts the object into "writing"
     * mode (i.e. creates a lock file. So you'd better start writing data and be sure to finish it
     * off).
     *
     * @param filePrefix string prefix for all files, must not contain "_" chars
     * @param dataFileDir the path to directory to create the data file in
     * @param index the index number for this file
     * @param creationTime the time stamp for the creation time for this file
     * @param compactionLevel the compaction level for this file
     * @param dataBufferSize the size of the memory mapped data buffer to use for writing data items
     */
    public DataFileWriter(
            final String filePrefix,
            final Path dataFileDir,
            final int index,
            final Instant creationTime,
            final int compactionLevel,
            final int dataBufferSize,
            final long maxFileSize)
            throws IOException {
        this.dataBufferSize = dataBufferSize;

        final long maxNumWritingWindows = maxFileSize / dataBufferSize;
        if (maxNumWritingWindows > 1L << 24) {
            throw new IllegalArgumentException("Data buffer size " + dataBufferSize + " is too small"
                    + " or max file size " + maxFileSize + " is too large");
        }
        this.writingWindows = new AtomicReferenceArray<>((int) maxNumWritingWindows);

        path = createDataFilePath(
                filePrefix, dataFileDir, index, creationTime, compactionLevel, DataFileCommon.FILE_EXTENSION);
        Files.createFile(path);
        fileChannel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        // Items count will be updated after the file is completely written
        metadata = new DataFileMetadata(index, creationTime, compactionLevel, 0);

        final int headerSize = writeHeader();
        currentWriteOffset.set(headerSize);
        getWritingWindow(0).bytesWritten(headerSize);
    }

    private int writeHeader() throws IOException {
        final int metadataSize = metadata.metadataSizeInBytes();
        final ByteBuffer buf = ByteBuffer.allocate(metadataSize);
        metadata.writeTo(BufferedData.wrap(buf));
        assert buf.remaining() == 0;
        buf.flip();
        MerkleDbFileUtils.completelyWrite(fileChannel, buf, 0);
        return metadataSize;
    }

    /**
     * Get the path for the file being written. Useful when needing to get a reader to the file.
     *
     * @return file path
     */
    public Path getPath() {
        return path;
    }

    /**
     * Get file metadata for the written file.
     *
     * @return data file metadata
     */
    public DataFileMetadata getMetadata() {
        return metadata;
    }

    /**
     * Gets a writing window with a given index, initializing (mapping from the file) it
     * if needed.
     */
    private WritingWindow getWritingWindow(final int windowIndex) throws IOException {
        WritingWindow window = writingWindows.get(windowIndex);
        // Two approaches are viable here. First, double check with synchronization. Second,
        // CAS with delete. To delete a newly created writing window includes memory segment
        // unmap, which is supposed to be slower than a synchronized block, so let's use
        // the former approach
        if (window == null) {
            synchronized (this) {
                window = writingWindows.get(windowIndex);
                if (window == null) {
                    window = new WritingWindow(windowIndex);
                    writingWindows.set(windowIndex, window);
                }
            }
        }
        return window;
    }

    /**
     * Returns a temp MemoryData buffer with position set to 0 and limit set to sizeToWrite. The
     * buffer can be used on the current thread only.
     */
    private MemoryData getTempLocalWriteBuffer(final int sizeToWrite) {
        MemoryData out = WRITE_CACHE.get();
        if ((out == null) || (out.capacity() < sizeToWrite)) {
            final MemorySegment segment = MemorySegment.ofArray(new byte[sizeToWrite]);
            SEGMENT_CACHE.set(segment);
            out = MemoryData.wrap(segment);
            WRITE_CACHE.set(out);
        } else {
            out.position(0);
            out.limit(sizeToWrite);
        }
        return out;
    }

    /**
     * This method is called, when a data item is written. If the data item is written completely
     * into a single writing window, this window is notified that all data item bytes are written
     * to it. If write happened on a boundary of two windows, both windows are notified
     * accordingly. Once all bytes are written for any window, the window is unmapped from memory.
     */
    private void bytesWritten(final long fileOffset, final int sizeToWrite) throws IOException {
        final int writingWindowIndex = Math.toIntExact(fileOffset / dataBufferSize);
        final long nextOffset = fileOffset + sizeToWrite;
        if ((nextOffset - 1) / dataBufferSize == writingWindowIndex) {
            // Same window
            getWritingWindow(writingWindowIndex).bytesWritten(sizeToWrite);
        } else {
            // Some bytes are written into the next window
            assert nextOffset / dataBufferSize == writingWindowIndex + 1;
            final int currentWindowBytesWritten = Math.toIntExact(dataBufferSize - fileOffset % dataBufferSize);
            assert currentWindowBytesWritten > 0;
            assert currentWindowBytesWritten < sizeToWrite;
            getWritingWindow(writingWindowIndex).bytesWritten(currentWindowBytesWritten);
            final int nextWindowBytesWritten = Math.toIntExact(nextOffset % dataBufferSize);
            assert nextWindowBytesWritten > 0;
            getWritingWindow(writingWindowIndex + 1).bytesWritten(nextWindowBytesWritten);
        }
    }

    /**
     * Store a data item in file returning location it was stored at. The data item is written
     * with the {@link DataFileCommon#FIELD_DATAFILE_ITEMS} tag.
     *
     * @param dataItem the data item to write
     * @return the data location of written data in bytes
     * @throws IOException if there was a problem appending data to file
     */
    public long storeDataItem(final BufferedData dataItem) throws IOException {
        return storeDataItem(o -> o.writeBytes(dataItem), Math.toIntExact(dataItem.remaining()));
    }

    /**
     * Store a data item in file returning location it was stored at. The data item is written
     * with the {@link DataFileCommon#FIELD_DATAFILE_ITEMS} tag.
     *
     * @param dataItemWriter the data item to write
     * @param dataItemSize the data item size, in bytes
     * @return the data location of written data in bytes
     * @throws IOException if there was a problem appending data to the file
     */
    public long storeDataItem(final Consumer<WritableSequentialData> dataItemWriter, final int dataItemSize)
            throws IOException {
        final int sizeToWrite = ProtoWriterTools.sizeOfDelimited(FIELD_DATAFILE_ITEMS, dataItemSize);
        return store(
                out -> ProtoWriterTools.writeDelimited(out, FIELD_DATAFILE_ITEMS, dataItemSize, dataItemWriter),
                sizeToWrite);
    }

    /**
     * Store a data item in file returning location it was stored at. The data item is written
     * as is, assuming the provided data item buffer already has the {@link
     * DataFileCommon#FIELD_DATAFILE_ITEMS} tag and the item length.
     *
     * <p>This method is very similar to {@link #storeDataItem(Consumer, int)}. They are not
     * refactored to a single method to avoid lambda / method handle performance overhead.
     *
     * @param dataItemWithTag the data item to write
     * @return the data location of written data in bytes
     * @throws IOException if there was a problem appending data to the file
     */
    public long storeDataItemWithTag(final BufferedData dataItemWithTag) throws IOException {
        final int sizeToWrite = Math.toIntExact(dataItemWithTag.remaining());
        return store(out -> out.writeBytes(dataItemWithTag), sizeToWrite);
    }

    private long store(final Consumer<MemoryData> writer, final int sizeToWrite) throws IOException {
        if (closed.get()) {
            throw new IOException("Data file is already closed");
        }

        if (sizeToWrite > dataBufferSize) {
            throw new IOException(
                    ERROR_DATA_ITEM_TOO_LARGE + " dataSize=" + sizeToWrite + ", bufferSize=" + dataBufferSize);
        }

        final long fileOffset = currentWriteOffset.getAndAdd(sizeToWrite);
        final int writingWindowIndex = Math.toIntExact(fileOffset / dataBufferSize);
        final WritingWindow writingWindow = getWritingWindow(writingWindowIndex);

        try {
            final MemoryData out = getTempLocalWriteBuffer(sizeToWrite);
            writer.accept(out);
            // double check that we wrote the expected number of bytes
            if (out.remaining() != 0) {
                throw new IOException("Estimated size / written bytes mismatch: expected=" + sizeToWrite + " written="
                        + (sizeToWrite - out.remaining()));
            }

            // The segment contains the same data as out above
            final MemorySegment segment = SEGMENT_CACHE.get();
            final long writingOffset = fileOffset % dataBufferSize;
            MemorySegment.copy(segment, 0, writingWindow.writeBuffer, writingOffset, sizeToWrite);
        } finally {
            bytesWritten(fileOffset, sizeToWrite);
        }

        itemsCount.incrementAndGet();
        // return the offset where we wrote the data
        return DataFileCommon.dataLocation(metadata.getIndex(), fileOffset);
    }

    /**
     * Release all the resources like mapped buffer and file channel.
     */
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        // total file size is where the current writing pos is
        final long totalFileSize = currentWriteOffset.get();

        // Cleanup the last writing window
        if (totalFileSize % dataBufferSize != 0) {
            getWritingWindow((int) ((totalFileSize - 1) / dataBufferSize)).close();
        }

        // Update metadata with the final items count and rewrite the header.
        // The header size is identical to the original because FIELD_ITEMS_COUNT
        // is FIXED64 (always 8 bytes regardless of value), so this cannot
        // overwrite data items.
        metadata = new DataFileMetadata(
                metadata.getIndex(), metadata.getCreationDate(), metadata.getCompactionLevel(), itemsCount.get());
        writeHeader();

        // Truncate after header rewrite
        fileChannel.truncate(totalFileSize);

        fileChannel.close();
    }

    private class WritingWindow {

        private final Arena arena = Arena.ofShared();

        // FUTURE WORK: use MemoryData to directly write data items to
        private final MemorySegment writeBuffer;

        private final AtomicInteger bytesLeftToWrite;

        private final AtomicBoolean closed = new AtomicBoolean(false);

        WritingWindow(final int windowIndex) throws IOException {
            final long fileOffset = (long) windowIndex * dataBufferSize;
            // Mapped segment size is 2 x data buffer size to make sure writing windows are overlapped
            writeBuffer = fileChannel.map(MapMode.READ_WRITE, fileOffset, dataBufferSize * 2L, arena);
            bytesLeftToWrite = new AtomicInteger(dataBufferSize);
        }

        public void bytesWritten(final int bytes) {
            final int left = bytesLeftToWrite.addAndGet(-bytes);
            assert left >= 0;
            if (left == 0) {
                close();
            }
        }

        public void close() {
            if (!closed.compareAndSet(false, true)) {
                throw new RuntimeException("Double close");
            }
            assert bytesLeftToWrite.get() >= 0;
            arena.close();
        }
    }
}