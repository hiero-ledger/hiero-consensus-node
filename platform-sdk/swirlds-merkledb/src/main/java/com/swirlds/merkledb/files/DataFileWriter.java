// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_ITEMS;
import static com.swirlds.merkledb.files.DataFileCommon.PAGE_SIZE;
import static com.swirlds.merkledb.files.DataFileCommon.createDataFilePath;

import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import java.io.IOException;
import java.nio.BufferOverflowException;
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
 * Writer for creating a data file. A data file contains a number of data items. Each data item can
 * be variable or fixed size and is considered as a black box. All access to contents of the data
 * item is done via the BaseSerializer.
 *
 * <p><b>This is designed to be used from a single thread.</b>
 *
 * <p>At the end of the file it is padded till a 4096 byte page boundary then a footer page is
 * written by DataFileMetadata.
 *
 * <p>Protobuf schema: see {@link DataFileReader} for details.
 */
public final class DataFileWriter {

    private static final String ERROR_DATA_ITEM_TOO_LARGE =
            "Data item is too large to write to a data file. Increase data file mapped byte buffer size";

    /** Mapped buffer size */
    private static final int MMAP_BUF_SIZE = PAGE_SIZE * 1024 * 64;

    /**
     * The current mapped byte buffer used for writing. When overflowed, it is released, and another
     * buffer is mapped from the file channel.
     */
    private MappedByteBuffer writingMmap;
    /**
     * Offset, in bytes, of the current mapped byte buffer in the file channel. After the file is
     * completely written and closed, this field value is equal to the file size.
     */
    private long mmapPositionInFile = 0;
    /* */
    private BufferedData writingPbjData;

    private MappedByteBuffer writingHeaderMmap;
    private BufferedData writingHeaderPbjData;

    /** The path to the data file we are writing */
    private final Path path;
    /** File metadata */
    private final DataFileMetadata metadata;
    /**
     * Count of the number of data items we have written so far. Ready to be stored in footer
     * metadata
     */
    private long dataItemCount = 0;

    /**
     * Create a new data file in the given directory, in append mode. Puts the object into "writing"
     * mode (i.e. creates a lock file. So you'd better start writing data and be sure to finish it
     * off).
     *
     * @param filePrefix string prefix for all files, must not contain "_" chars
     * @param dataFileDir the path to directory to create the data file in
     * @param index the index number for this file
     * @param creationTime the time stamp for the creation time for this file
     */
    public DataFileWriter(
            final String filePrefix,
            final Path dataFileDir,
            final int index,
            final Instant creationTime,
            final int compactionLevel)
            throws IOException {
        this.path = createDataFilePath(filePrefix, dataFileDir, index, creationTime, DataFileCommon.FILE_EXTENSION);
        metadata = new DataFileMetadata(
                0, // data item count will be updated later in finishWriting()
                index,
                creationTime,
                compactionLevel);
        Files.createFile(path);
        writeHeader();
    }

    /**
     * Maps the writing byte buffer to the given position in the file. Byte buffer size is always
     * {@link #MMAP_BUF_SIZE}. Previous mapped byte buffer, if not null, is released.
     *
     * @param newMmapPos new mapped byte buffer position in the file, in bytes
     * @throws IOException if I/O error(s) occurred
     */
    private void moveWritingBuffer(final long newMmapPos) throws IOException {
        try (final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            final MappedByteBuffer newMmap = channel.map(MapMode.READ_WRITE, newMmapPos, MMAP_BUF_SIZE);
            if (newMmap == null) {
                throw new IOException("Failed to map file channel to memory");
            }
            if (writingMmap != null) {
                MemoryUtils.closeMmapBuffer(writingMmap);
            }
            mmapPositionInFile = newMmapPos;
            writingMmap = newMmap;
            writingPbjData = BufferedData.wrap(writingMmap);
        }
    }

    private void writeHeader() throws IOException {
        try (final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            writingHeaderMmap = channel.map(MapMode.READ_WRITE, 0, 1024);
            writingHeaderPbjData = BufferedData.wrap(writingHeaderMmap);
            metadata.writeTo(writingHeaderPbjData);
        }
        // prepare to write data items
        moveWritingBuffer(writingHeaderPbjData.position());
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
    public synchronized long storeDataItem(final BufferedData dataItem) throws IOException {
        // find offset for the start of this new data item, we assume we always write data in a
        // whole number of blocks
        long currentWritingMmapPos = writingPbjData.position();
        final long byteOffset = mmapPositionInFile + currentWritingMmapPos;
        // write serialized data
        final int size = Math.toIntExact(dataItem.remaining());
        final int sizeToWrite = ProtoWriterTools.sizeOfDelimited(FIELD_DATAFILE_ITEMS, size);
        if (writingPbjData.remaining() < sizeToWrite) {
            moveWritingBuffer(byteOffset);
            currentWritingMmapPos = 0;
        }
        try {
            ProtoWriterTools.writeDelimited(writingPbjData, FIELD_DATAFILE_ITEMS, size, o -> o.writeBytes(dataItem));
            if (writingPbjData.position() != currentWritingMmapPos + sizeToWrite) {
                throw new IOException("Estimated size / written bytes mismatch: expected=" + sizeToWrite + " written="
                        + (writingPbjData.position() - currentWritingMmapPos));
            }
        } catch (final BufferOverflowException e) {
            // Buffer overflow here means the mapped buffer is smaller than even a single data item
            throw new IOException(ERROR_DATA_ITEM_TOO_LARGE, e);
        }
        // increment data item counter
        dataItemCount++;
        // return the offset where we wrote the data
        return DataFileCommon.dataLocation(metadata.getIndex(), byteOffset);
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
        // find offset for the start of this new data item, we assume we always write data in a
        // whole number of blocks
        final long currentWritingMmapPos = writingPbjData.position();
        final long byteOffset = mmapPositionInFile + currentWritingMmapPos;
        // write serialized data
        if (writingPbjData.remaining() < ProtoWriterTools.sizeOfDelimited(FIELD_DATAFILE_ITEMS, dataItemSize)) {
            moveWritingBuffer(byteOffset);
        }
        try {
            ProtoWriterTools.writeDelimited(writingPbjData, FIELD_DATAFILE_ITEMS, dataItemSize, dataItemWriter);
        } catch (final BufferOverflowException e) {
            // Buffer overflow here means the mapped buffer is smaller than even a single data item
            throw new IOException(ERROR_DATA_ITEM_TOO_LARGE, e);
        }
        // increment data item counter
        dataItemCount++;
        // return the offset where we wrote the data
        return DataFileCommon.dataLocation(metadata.getIndex(), byteOffset);
    }

    /**
     * When you finished append to a new file, call this to seal the file and make it read only for
     * reading.
     *
     * @throws IOException if there was a problem sealing file or opening again as read only
     */
    public synchronized void finishWriting() throws IOException {
        // total file size is where the current writing pos is
        final long totalFileSize = mmapPositionInFile + writingPbjData.position();
        // update data item count in the metadata and in the file
        // not that updateDataItemCount() messes up with writing buffer state (position), but
        // the buffer will be closed below anyway
        metadata.updateDataItemCount(writingHeaderPbjData, dataItemCount);
        // release all the resources
        MemoryUtils.closeMmapBuffer(writingHeaderMmap);
        MemoryUtils.closeMmapBuffer(writingMmap);

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.truncate(totalFileSize);
            // after finishWriting(), mmapPositionInFile should be equal to the file size
            mmapPositionInFile = totalFileSize;
        }
    }
}
