// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_ITEMS;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_METADATA;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Iterator class for iterating over data items in a data file created by {@link  DataFileWriter}.
 * It is designed to be used in a <code>while(iter.next()){...}</code>
 * loop, where you can then read the data items info for current item with {@link #getDataItemData()} and {@link #getDataItemDataLocation()}.
 *
 * <p>It is designed to be used from a single thread.
 *
 * @see DataFileReader
 */
public final class DataFileIterator implements AutoCloseable {

    /** Input stream this iterator is reading from */
    private final BufferedInputStream inputStream;
    /** Readable data on top of the input stream */
    private final ReadableSequentialData in;
    /** The file metadata read from the end of file */
    private final DataFileMetadata metadata;
    /** The path to the file we are iterating over */
    private final Path path;

    /** Buffer that is reused for reading each data item */
    private BufferedData dataItemBuffer;
    /** Index of current data item this iterator is reading, zero being the first item, -1 being before start */
    private long currentDataItem = -1;
    /** The offset in bytes from start of file to the beginning of the current item. */
    private long currentDataItemFilePosition = 0;
    /** True if this iterator has been closed */
    private boolean closed = false;

    /**
     * Create a new DataFileIterator on an existing file.
     *
     * @param dbConfig MerkleDb config
     * @param path
     * 		The path to the file to read.
     * @param metadata
     * 		The metadata read from the file.
     * @throws IOException
     * 		if there was a problem creating a new InputStream on the file at path
     */
    public DataFileIterator(final MerkleDbConfig dbConfig, final Path path, final DataFileMetadata metadata)
            throws IOException {
        this.path = path;
        this.metadata = metadata;
        this.inputStream = new BufferedInputStream(
                Files.newInputStream(path, StandardOpenOption.READ), dbConfig.iteratorInputBufferBytes());
        this.in = new ReadableStreamingData(inputStream);
        this.in.limit(Files.size(path));
    }

    /**
     * Get the path for the data file.
     */
    public Path getPath() {
        return path;
    }

    /**
     * Get the metadata for the data file.
     *
     * @return File's metadata
     */
    public DataFileMetadata getMetadata() {
        return metadata;
    }

    /**
     * Close the iterator.
     *
     * @throws IOException if this resource cannot be closed
     */
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            dataItemBuffer = null;
            inputStream.close();
        }
    }

    /**
     * Advance to the next dataItem.
     *
     * @return true if a dataItem was read or false if the end of the file has been reached.
     * @throws IOException
     * 		If there was a problem reading from file.
     */
    public boolean next() throws IOException {
        if (closed) {
            throw new IllegalStateException("Cannot read from a closed iterator");
        }

        while (in.hasRemaining()) {
            currentDataItemFilePosition = in.position();
            final int tag = in.readVarInt(false);
            final int fieldNum = tag >> TAG_FIELD_OFFSET;

            if (fieldNum == FIELD_DATAFILE_ITEMS.number()) {
                final int currentDataItemSize = in.readVarInt(false);
                dataItemBuffer = fillBuffer(currentDataItemSize);
                currentDataItem++;
                return true;
            } else if (fieldNum == FIELD_DATAFILE_METADATA.number()) {
                final int metadataSize = in.readVarInt(false);
                in.skip(metadataSize);
            } else {
                throw new IllegalArgumentException("Unknown data file field: " + fieldNum);
            }
        }

        return false;
    }

    /**
     * Get the current dataItems data. This is a shared buffer and must NOT be leaked from
     * the call site or modified directly.
     *
     * @return buffer containing the key and value data. This will return null if the iterator has
     * 		been closed, or if the iterator is in the before-first or after-last states.
     */
    public BufferedData getDataItemData() {
        return dataItemBuffer;
    }

    /**
     * Get the data location (file + offset) for the current data item.
     *
     * @return current data item location
     */
    public long getDataItemDataLocation() {
        return DataFileCommon.dataLocation(metadata.getIndex(), currentDataItemFilePosition);
    }

    /** toString for debugging */
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("fileIndex", metadata.getIndex())
                .append("currentDataItemIndex", currentDataItem)
                .append("currentDataItemByteOffset", currentDataItemFilePosition)
                .append("fileName", path.getFileName())
                .append("metadata", metadata)
                .toString();
    }

    /**
     * Equals for use when comparing in collections, based on matching file paths and metadata
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DataFileIterator that = (DataFileIterator) o;
        return path.equals(that.getPath()) && metadata.equals(that.getMetadata());
    }

    /**
     * hashCode for use when comparing in collections, based on file path and metadata
     */
    @Override
    public int hashCode() {
        return Objects.hash(path, metadata);
    }

    // =================================================================================================================
    // Private methods

    /**
     * Reads bytesToRead bytes from the current data item
     * @param bytesToRead bytes to read
     * @return ByteBuffer containing requested bytes
     * @throws IOException if request can not be completed
     */
    private BufferedData fillBuffer(int bytesToRead) throws IOException {
        if (bytesToRead <= 0) {
            throw new IOException("Malformed file [" + path + "], data item [" + currentDataItem
                    + "], requested bytes [" + bytesToRead + "]");
        }

        // Create or resize the buffer if necessary
        if (dataItemBuffer == null || dataItemBuffer.capacity() < bytesToRead) {
            dataItemBuffer = BufferedData.allocate(bytesToRead);
        }

        dataItemBuffer.position(0);
        dataItemBuffer.limit(bytesToRead);
        final long bytesRead = in.readBytes(dataItemBuffer);
        if (bytesRead != bytesToRead) {
            throw new IOException("Couldn't read " + bytesToRead + " bytes, data item [" + currentDataItem
                    + "], requested bytes [" + bytesToRead + "]");
        }

        dataItemBuffer.position(0);
        return dataItemBuffer;
    }
}
