// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.base.units.UnitConstants.KIBIBYTES_TO_BYTES;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_ITEMS;
import static com.swirlds.merkledb.files.DataFileCommon.PAGE_SIZE;
import static com.swirlds.merkledb.files.DataFileCommon.createDataFilePath;

import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.function.Consumer;
import org.hiero.base.utility.MemoryUtils;

/**
 * Class for creating and sequentially writing to the file.
 * A data file contains a header containing {@link DataFileMetadata} followed by data items.
 * Each data item can be variable or fixed size and is considered as a black box.
 *
 * <p>{@link #close()} must be called after done wiring data using {@link #storeDataItem(BufferedData)} any number of times.
 * The implementation doesn't control the file size.
 *
 * <p>Internally, the data items are written to a memory mapped file using {@link MappedByteBuffer} of fixed size, that could be provided in constructor.
 * This buffer is moved to the current file position when needed.
 *
 * <p><b>This is designed to be used from a single thread.</b>
 *
 * <p>{@link DataFileReader} or {@link DataFileIterator} can be used to read file back and access data items.
 */
public final class DataFileWriter implements AutoCloseable {

    /**
     * Default buffer size for writing into the file is 64 Mb
     */
    private static final int DEFAULT_BUF_SIZE = PAGE_SIZE * KIBIBYTES_TO_BYTES * 16;

    private static final String ERROR_DATA_ITEM_TOO_LARGE =
            "Data item is too large to write to a data file. Increase data file mapped byte buffer size";

    /**
     * The current mapped byte buffer used for writing. When overflowed, it is released, and another
     * buffer is mapped from the file channel.
     */
    private MappedByteBuffer mappedDataBuffer;

    /**
     * Offset, in bytes, of the current mapped byte buffer in the file channel.
     */
    private long bufferPositionInFile;

    private BufferedData dataBuffer;

    /** The path to the data file we are writing */
    private final Path path;

    private final FileChannel fileChannel;

    /** File metadata */
    private final DataFileMetadata metadata;

    private final long dataBufferSize;

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

        path = createDataFilePath(filePrefix, dataFileDir, index, creationTime, DataFileCommon.FILE_EXTENSION);
        Files.createFile(path);
        fileChannel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        metadata = new DataFileMetadata(index, creationTime, compactionLevel);

        bufferPositionInFile = writeHeader();
        moveWritingBuffer(bufferPositionInFile);
    }

    /**
     * Maps the writing byte buffer to the given position in the file. Byte buffer size is always
     * {@link #dataBufferSize}. Previous mapped byte buffer, if not null, is released.
     *
     * @param startPosition new mapped byte buffer position in the file, in bytes
     * @throws IOException if I/O error(s) occurred
     */
    private void moveWritingBuffer(final long startPosition) throws IOException {
        final MappedByteBuffer newBuffer = fileChannel.map(MapMode.READ_WRITE, startPosition, dataBufferSize);
        if (mappedDataBuffer != null) {
            MemoryUtils.closeMmapBuffer(mappedDataBuffer);
        }
        bufferPositionInFile = startPosition;
        mappedDataBuffer = newBuffer;
        dataBuffer = BufferedData.wrap(mappedDataBuffer);
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
     * Store data item in file returning location it was stored at.
     *
     * @param dataItem the data item to write
     * @return the data location of written data in bytes
     * @throws IOException if there was a problem appending data to file
     */
    public long storeDataItem(final BufferedData dataItem) throws IOException {
        return storeDataItem(o -> o.writeBytes(dataItem), Math.toIntExact(dataItem.remaining()));
    }

    /**
     * Store data item in file returning location it was stored at.
     *
     * @param dataItemWriter the data item to write
     * @param dataItemSize the data item size, in bytes
     * @return the data location of written data in bytes
     * @throws IOException if there was a problem appending data to file
     */
    public synchronized long storeDataItem(final Consumer<BufferedData> dataItemWriter, final int dataItemSize)
            throws IOException {
        if (closed) {
            throw new IOException("Data file is already closed");
        }

        final long fileOffset = getCurrentFilePosition();
        final int sizeToWrite = ProtoWriterTools.sizeOfDelimited(FIELD_DATAFILE_ITEMS, dataItemSize);

        if (sizeToWrite > dataBufferSize) {
            throw new IOException(
                    ERROR_DATA_ITEM_TOO_LARGE + " dataSize=" + sizeToWrite + ", bufferSize=" + dataBufferSize);
        }

        // if there is not enough space in the current mapped buffer,
        // we need to move it to start at current file offset
        if (dataBuffer.remaining() < sizeToWrite) {
            moveWritingBuffer(fileOffset);
        }

        // write actual data
        ProtoWriterTools.writeDelimited(dataBuffer, FIELD_DATAFILE_ITEMS, dataItemSize, dataItemWriter);

        // double check that we wrote the expected number of bytes
        if (getCurrentFilePosition() != fileOffset + sizeToWrite) {
            throw new IOException("Estimated size / written bytes mismatch: expected=" + sizeToWrite + " written="
                    + (getCurrentFilePosition() - fileOffset));
        }

        // return the offset where we wrote the data
        return DataFileCommon.dataLocation(metadata.getIndex(), fileOffset);
    }

    /**
     * Release all the resources like mapped buffer and file channel.
     */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }

        // total file size is where the current writing pos is
        final long totalFileSize = bufferPositionInFile + dataBuffer.position();

        // release all the resources
        MemoryUtils.closeMmapBuffer(mappedDataBuffer);

        fileChannel.truncate(totalFileSize);
        bufferPositionInFile = totalFileSize;

        fileChannel.close();
        closed = true;
    }

    private long getCurrentFilePosition() {
        return bufferPositionInFile + dataBuffer.position();
    }
}
