// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.base.units.UnitConstants.KIBIBYTES_TO_BYTES;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_ITEMS;
import static com.swirlds.merkledb.files.DataFileCommon.PAGE_SIZE;
import static com.swirlds.merkledb.files.DataFileCommon.createDataFilePath;

import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import org.hiero.base.utility.MemoryUtils;

/**
 * Class for creating and sequentially writing to the file. A data file contains a header
 * containing {@link DataFileMetadata} followed by data items. Each data item is considered
 * as a black box.
 *
 * <p>{@link #close()} must be called after done writing data using {@link #storeDataItem(BufferedData)},
 * or {@link #storeDataItem(Consumer, int)}, or {@link #storeDataItemWithTag(BufferedData)}
 * any number of times. The implementation doesn't control the file size.
 *
 * <p>Internally, the data items are written to a memory mapped file using {@link MappedByteBuffer}
 * of fixed size, that could be provided in constructor. This buffer is moved to the current
 * file position when needed.
 *
 * <p><b>This class is NOT thread safe.</b>
 *
 * <p>{@link DataFileReader} or {@link DataFileIterator} can be used to read file back and access data items.
 */
public final class DataFileWriter {

    /**
     * Default buffer size for writing into the file is 64 Mb
     */
    private static final int DEFAULT_BUF_SIZE = PAGE_SIZE * KIBIBYTES_TO_BYTES * 16;

    private static final String ERROR_DATA_ITEM_TOO_LARGE =
            "Data item is too large to write to a data file. Increase data file mapped byte buffer size";

    private static final ThreadLocal<ByteBuffer> BUFFER_CACHE = new ThreadLocal<>();
    private static final ThreadLocal<BufferedData> WRITER_CACHE = new ThreadLocal<>();

    private final AtomicReferenceArray<WritingWindow> writingWindows =
            new AtomicReferenceArray<>((int) ((1L << 40) / DEFAULT_BUF_SIZE));

    /**
     * Offset, in bytes, of the current mapped byte buffer in the file channel.
     */
    private final AtomicLong currentWriteOffset = new AtomicLong(0);

    /** The path to the data file we are writing */
    private final Path path;

    private final FileChannel fileChannel;

    /** File metadata */
    private DataFileMetadata metadata;

    /** Total number of items written to this file */
    private final AtomicLong itemsCount = new AtomicLong(0);

    private final long dataBufferSize;
    private final long halfBufferSize;

    private final long headerSize;

    /**
     * Indicates if this file writer has been closed. Only set and accessed on the
     * writing thread.
     */
    private boolean closed = false;

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
        this(filePrefix, dataFileDir, index, creationTime, compactionLevel, DEFAULT_BUF_SIZE);
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
            final long dataBufferSize)
            throws IOException {
        this.dataBufferSize = dataBufferSize;
        this.halfBufferSize = dataBufferSize / 2;

        path = createDataFilePath(
                filePrefix, dataFileDir, index, creationTime, compactionLevel, DataFileCommon.FILE_EXTENSION);
        Files.createFile(path);
        fileChannel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        // Items count will be updated after the file is completely written
        metadata = new DataFileMetadata(index, creationTime, compactionLevel, 0);

        headerSize = writeHeader();
        currentWriteOffset.set(headerSize);
    }

    private long writeHeader() throws IOException {
        final MappedByteBuffer headerMappedBuffer = fileChannel.map(MapMode.READ_WRITE, 0, 1024);
        final BufferedData headerBuffer = BufferedData.wrap(headerMappedBuffer);
        try {
            metadata.writeTo(headerBuffer);
            return headerBuffer.position();
        } finally {
            MemoryUtils.closeMmapBuffer(headerMappedBuffer);
        }
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

    private WritingWindow prepareWritingWindow(final long offset) throws IOException {
        final int writingIndex = (int) (offset / halfBufferSize);
        WritingWindow writingWindow = writingWindows.get(writingIndex);
        if (writingWindow == null) {
            writingWindow = new WritingWindow(writingIndex * halfBufferSize);
            writingWindows.set(writingIndex, writingWindow);
        }
        writingWindow.retain();
        return writingWindow;
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
    public long storeDataItem(final Consumer<BufferedData> dataItemWriter, final int dataItemSize) throws IOException {
        if (closed) {
            throw new IOException("Data file is already closed");
        }

        final int sizeToWrite = ProtoWriterTools.sizeOfDelimited(FIELD_DATAFILE_ITEMS, dataItemSize);
        if (sizeToWrite > halfBufferSize) {
            throw new IOException(
                    ERROR_DATA_ITEM_TOO_LARGE + " dataSize=" + sizeToWrite + ", bufferSize=" + halfBufferSize);
        }

        final WritingWindow writingWindow;
        final long fileOffset;
        synchronized (this) {
            fileOffset = currentWriteOffset.getAndAdd(sizeToWrite);
            writingWindow = prepareWritingWindow(fileOffset);
        }
        assert writingWindow.refCount.get() > 0;

        try {
            final long writingOffset = fileOffset % halfBufferSize;

//            final BufferedData writeBuf = writingWindow.writeBuffer.slice(writingOffset, sizeToWrite);
//            ProtoWriterTools.writeDelimited(writeBuf, FIELD_DATAFILE_ITEMS, dataItemSize, dataItemWriter);

//            final byte[] writeBytes = new byte[sizeToWrite];
//            final BufferedData writeBuf = BufferedData.wrap(writeBytes);
//            ProtoWriterTools.writeDelimited(writeBuf, FIELD_DATAFILE_ITEMS, dataItemSize, dataItemWriter);
//            writingWindow.mappedBuffer.put((int) writingOffset, writeBytes);

            ByteBuffer writeBB = BUFFER_CACHE.get();
            final BufferedData writeBuf;
            if ((writeBB == null) || (writeBB.capacity() < sizeToWrite)) {
                writeBB = ByteBuffer.allocate(sizeToWrite);
                BUFFER_CACHE.set(writeBB);
                writeBuf = BufferedData.wrap(writeBB);
                WRITER_CACHE.set(writeBuf);
            } else {
                writeBuf = WRITER_CACHE.get();
            }
            writeBuf.position(0);
            writeBuf.limit(sizeToWrite);
            ProtoWriterTools.writeDelimited(writeBuf, FIELD_DATAFILE_ITEMS, dataItemSize, dataItemWriter);
            writingWindow.mappedBuffer.put((int) writingOffset, writeBB, 0, sizeToWrite);

            // double check that we wrote the expected number of bytes
            if (writeBuf.remaining() != 0) {
                throw new IOException("Estimated size / written bytes mismatch: expected=" + sizeToWrite + " written="
                        + (sizeToWrite - writeBuf.remaining()));
            }
        } finally {
            synchronized (this) {
                writingWindow.release();
                if ((fileOffset / halfBufferSize) != ((fileOffset + sizeToWrite) / halfBufferSize)) {
                    writingWindow.release();
                }
            }
        }

        itemsCount.incrementAndGet();
        // return the offset where we wrote the data
        return DataFileCommon.dataLocation(metadata.getIndex(), fileOffset);
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
        if (closed) {
            throw new IOException("Data file is already closed");
        }

        final int sizeToWrite = Math.toIntExact(dataItemWithTag.remaining());
        if (sizeToWrite > halfBufferSize) {
            throw new IOException(
                    ERROR_DATA_ITEM_TOO_LARGE + " dataSize=" + sizeToWrite + ", bufferSize=" + halfBufferSize);
        }

        final WritingWindow writingWindow;
        final long fileOffset;
        synchronized (this) {
            fileOffset = currentWriteOffset.getAndAdd(sizeToWrite);
            writingWindow = prepareWritingWindow(fileOffset);
        }
        assert writingWindow.refCount.get() > 0;

        try {
            final long writingOffset = fileOffset % halfBufferSize;
            final BufferedData writeBuffer = writingWindow.writeBuffer.slice(writingOffset, sizeToWrite);
            writeBuffer.writeBytes(dataItemWithTag);

            // double check that we wrote the expected number of bytes
            if (writeBuffer.remaining() != 0) {
                throw new IOException("Estimated size / written bytes mismatch: expected=" + sizeToWrite + " written="
                        + (sizeToWrite - writeBuffer.remaining()));
            }
        } finally {
            synchronized (this) {
                writingWindow.release();
                if ((fileOffset / halfBufferSize) != ((fileOffset + sizeToWrite) / halfBufferSize)) {
                    writingWindow.release();
                }
            }
        }

        itemsCount.incrementAndGet();
        // return the offset where we wrote the data
        return DataFileCommon.dataLocation(metadata.getIndex(), fileOffset);
    }

    /**
     * Release all the resources like mapped buffer and file channel.
     */
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        // total file size is where the current writing pos is
        final long totalFileSize = currentWriteOffset.get();

        if (totalFileSize > headerSize) {
            // release all the resources
            final int lastWritingIndex = (int) (totalFileSize / halfBufferSize);
            final WritingWindow lastWritingWindow = writingWindows.get(lastWritingIndex);
            if (lastWritingWindow != null) {
                lastWritingWindow.release();
            }
        }

//        for (int i = 0; i < writingWindows.length(); i++) {
//            assert (writingWindows.get(i) == null) || (writingWindows.get(i).refCount.get() == 0);
//        }

        // Update metadata with the final items count and rewrite the header.
        // The header size is identical to the original because FIELD_ITEMS_COUNT
        // is FIXED64 (always 8 bytes regardless of value), so this cannot
        // overwrite data items.
        metadata = new DataFileMetadata(
                metadata.getIndex(), metadata.getCreationDate(), metadata.getCompactionLevel(), itemsCount.get());
        writeHeader();

        // Truncate after header rewrite. writeHeader() maps a 1024-byte buffer
        // which may extend the file beyond totalFileSize; truncating here removes
        // any zero padding.
        fileChannel.truncate(totalFileSize);

        fileChannel.close();
    }

    private class WritingWindow {

        private final AtomicInteger refCount = new AtomicInteger(1);
        public final MappedByteBuffer mappedBuffer;
        public final BufferedData writeBuffer;

        public WritingWindow(final long fileOffset) throws IOException {
            assert fileOffset % halfBufferSize == 0;
            mappedBuffer = fileChannel.map(MapMode.READ_WRITE, fileOffset, dataBufferSize);
            writeBuffer = BufferedData.wrap(mappedBuffer);
        }

        public void retain() {
            assert refCount.get() > 0;
            refCount.incrementAndGet();
        }

        public void release() {
            assert refCount.get() > 0;
            if (refCount.decrementAndGet() == 0) {
                MemoryUtils.closeMmapBuffer(mappedBuffer);
            }
        }
    }
}