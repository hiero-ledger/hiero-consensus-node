// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.files.DataFileCommon.*;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.merkledb.collections.OffHeapUser;
import com.swirlds.merkledb.utilities.MemoryUtils;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Class for creating and sequentially writing to the file.
 * A data file contains a header containing {@link DataFileMetadata} followed by data items.
 * Each data item can be variable or fixed size and is considered as a black box.
 *
 * <p>{@link #finishWriting()} must be called after done wiring data using {@link #storeDataItem(BufferedData)} any number of times.
 * This ensures that data item count long will be updated in metadata of file header and the file is closed.
 * Implementation doesn't control the file size.
 *
 * <p>Internally, the data items are written to a memory mapped file using {@link MappedByteBuffer} of fixed size, that could be provided in constructor.
 * This buffer is moved to the current file position when needed.
 *
 * <p><b>This is designed to be used from a single thread.</b>
 *
 * <p>{@link DataFileReader} or {@link DataFileIterator} can be used to read file back and access data items.
 */
public final class DataFileWriter implements OffHeapUser {

    private static final int HEADER_BUFFER_SIZE = 1024;

    /** The path to the data file we are writing */
    private final Path path;

    private final RandomAccessFile file;

    /** File metadata */
    private final DataFileMetadata metadata;

    private final MovingBuffer headerBuffer;
    private final MovingBuffer dataBuffer;

    private boolean closed = false;

    /**
     * Count of the number of data items we have written so far.
     * Used to update the metadata at in file header
     */
    private long dataItemCount = 0;

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
        // TODO maybe we can use 128Mb buffer size (see DataFileWriterBenchmark)
        this(filePrefix, dataFileDir, index, creationTime, compactionLevel, PAGE_SIZE * 1024 * 64);
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
            final int dataBufferSize)
            throws IOException {
        path = createDataFilePath(filePrefix, dataFileDir, index, creationTime, DataFileCommon.FILE_EXTENSION);
        file = new RandomAccessFile(path.toFile(), "rw");

        metadata = new DataFileMetadata(
                0, // data item count will be updated later in finishWriting()
                index,
                creationTime,
                compactionLevel);

        headerBuffer = new MovingBuffer(0, HEADER_BUFFER_SIZE);
        headerBuffer.write(FIELD_DATAFILE_METADATA, metadata.getSizeInBytes(), metadata::writeTo);

        dataBuffer = new MovingBuffer(headerBuffer.getCurrentFilePosition(), dataBufferSize);
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
        final long fileOffset = dataBuffer.write(FIELD_DATAFILE_ITEMS, dataItemSize, dataItemWriter);
        // increment data item counter
        dataItemCount++;
        // return the offset where we wrote the data
        return DataFileCommon.dataLocation(metadata.getIndex(), fileOffset);
    }

    /**
     * When you finished append to a new file, call this to seal the file and make it read only for
     * reading.
     *
     * @throws IOException if there was a problem sealing file or opening again as read only
     */
    public synchronized void finishWriting() throws IOException {
        if (closed) {
            throw new IllegalStateException("Data file is already closed");
        }

        // total file size is where the current writing pos is
        final long totalFileSize = dataBuffer.getCurrentFilePosition();
        // update data item count in the metadata and in the file
        // note that updateDataItemCount() messes up with writing buffer state (position), but the buffer will be closed
        // below anyway
        metadata.updateDataItemCount(headerBuffer.bufferedData, dataItemCount);
        // release all the resources
        headerBuffer.close();
        dataBuffer.close();

        file.setLength(totalFileSize);
        file.close();
        closed = true;
    }

    @Override
    public long getOffHeapConsumption() {
        if (closed) {
            return 0;
        }
        // Return the size of the data buffer even if not fully used but claimed to be used.
        // Buffer is released and not used after completing writing into the file, but we probably want to track
        // possible memory usage for each data file writer instance created.
        // During writing (and it is fast because sequential), we might not see benefit of reporting actual buffer space
        // taken.
        return dataBuffer.bufferSize;
    }

    /**
     * Helper class to encapsulate logic of writing data into the file using memory mapped buffers and moving buffer when needed.
     */
    private class MovingBuffer implements Closeable {

        private final long bufferSize;
        private long fileStartOffset = 0;
        private MappedByteBuffer bufferFileMapping;
        private BufferedData bufferedData;

        public MovingBuffer(long fileStartOffset, long bufferSize) throws IOException {
            this.bufferSize = bufferSize;
            move(fileStartOffset);
        }

        /**
         * Maps the writing byte buffer to the given position in the file.
         * Previous mapped byte buffer, if not null, is released.
         *
         * @param fileStartOffset new mapped byte buffer position in the file, in bytes
         * @throws IOException if I/O error(s) occurred
         */
        private void move(long fileStartOffset) throws IOException {
            this.fileStartOffset = fileStartOffset;

            // ensure we have disk space buffer can use
            file.setLength(fileStartOffset + bufferSize);

            final MappedByteBuffer newMapping = file.getChannel().map(MapMode.READ_WRITE, fileStartOffset, bufferSize);
            if (newMapping == null) {
                throw new IOException("Failed to map file channel to memory");
            }

            closeCurrentMappedBuffer();

            bufferFileMapping = newMapping;
            bufferedData = BufferedData.wrap(bufferFileMapping);
        }

        long getCurrentFilePosition() {
            return fileStartOffset + bufferedData.position();
        }

        /**
         * @param field field describing writing data
         * @param expectedSize expected data size that will be checked against the actual size after writing
         * @param writer writer of the data
         * @return the file offset where the data was written (start position of the data item)
         * @throws IOException
         */
        long write(final FieldDefinition field, final int expectedSize, final Consumer<BufferedData> writer)
                throws IOException {
            final long fileOffset = getCurrentFilePosition();
            final int sizeToWrite = ProtoWriterTools.sizeOfDelimited(field, expectedSize);

            if (sizeToWrite > bufferSize) {
                throw new IOException(DataFileCommon.ERROR_DATAITEM_TOO_LARGE + " dataSize=" + sizeToWrite
                        + ", bufferSize=" + bufferSize);
            }

            // if there is not enough space in the current mapped buffer,
            // we need to move it to start at current file offset
            if (bufferedData.remaining() < sizeToWrite) {
                move(fileOffset);
            }

            ProtoWriterTools.writeDelimited(bufferedData, field, expectedSize, writer);

            if (getCurrentFilePosition() != fileOffset + sizeToWrite) {
                throw new IOException("Estimated size / written bytes mismatch: expected=" + sizeToWrite + " written="
                        + (getCurrentFilePosition() - fileOffset));
            }

            return fileOffset;
        }

        @Override
        public void close() {
            closeCurrentMappedBuffer();
        }

        private void closeCurrentMappedBuffer() {
            if (bufferFileMapping != null) {
                MemoryUtils.closeMmapBuffer(bufferFileMapping);
                bufferFileMapping = null;
                bufferedData = null;
            }
        }
    }
}
