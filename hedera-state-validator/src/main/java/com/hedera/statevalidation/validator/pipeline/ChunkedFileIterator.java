// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.pipeline;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_ITEMS;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_METADATA;

import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.statevalidation.validator.model.DiskDataItem;
import com.hedera.statevalidation.validator.model.DiskDataItem.Type;
import com.swirlds.merkledb.files.DataFileCommon;
import com.swirlds.merkledb.files.DataFileMetadata;
import com.swirlds.merkledb.files.hashmap.Bucket;
import com.swirlds.merkledb.files.hashmap.ParsedBucket;
import com.swirlds.merkledb.utilities.MerkleDbFileUtils;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Iterator class for iterating over data items in a specific byte range (chunk) of a data file
 * created by {@link com.swirlds.merkledb.files.DataFileWriter}. It is designed to be used in a
 * {@code while(iter.next()){...}} loop, where you can then read the data items info for the
 * current item with {@link #getDataItemData()} and {@link #getDataItemDataLocation()}.
 *
 * <p>Unlike {@link com.swirlds.merkledb.files.DataFileIterator} which reads an entire file
 * sequentially, this iterator operates on a defined byte range, enabling parallel processing
 * of large data files by creating multiple iterator instances working on different chunks
 * of the same file concurrently.
 *
 * <p>When starting from a non-zero byte offset, the iterator automatically scans forward to
 * locate a valid data item boundary by validating the protobuf structure of encountered data.
 * Supported data types for boundary validation include {@link VirtualHashRecord},
 * {@link VirtualLeafBytes}, and {@link Bucket}.
 *
 * <p>Each iterator instance should be used from a single thread, but multiple instances
 * can safely operate on different byte ranges of the same file in parallel.
 *
 * @see com.swirlds.merkledb.files.DataFileIterator
 * @see com.swirlds.merkledb.files.DataFileReader
 */
public class ChunkedFileIterator implements AutoCloseable {
    /** File channel used for reading the data file and positioning within the byte range */
    private final FileChannel channel;
    /** The file metadata providing file index for data location calculation */
    private final DataFileMetadata metadata;

    /** The starting byte offset in the file for this chunk, adjusted to the nearest valid data item boundary */
    private long startByte;
    /** The ending byte offset in the file for this chunk (exclusive) */
    private final long endByte;

    /** The type of data items in this file, used for boundary validation when starting mid-file */
    private final DiskDataItem.Type dataType;

    /** Buffer size in bytes for both boundary scanning and stream reading operations */
    private final int bufferSizeBytes;

    /** Buffered input stream this iterator is reading from */
    private BufferedInputStream bufferedInputStream;
    /** Readable sequential data on top of the buffered input stream */
    private ReadableSequentialData in;
    /** Buffer that is reused for reading each data item */
    private BufferedData dataItemBuffer;
    /** The offset in bytes from start of file to the beginning of the current data item */
    private long currentDataItemFilePosition;
    /** True if this iterator has been closed */
    private boolean closed = false;

    /**
     * Create a new ChunkedFileIterator for a specific byte range of an existing data file.
     *
     * <p>If {@code startByte} is greater than zero, the constructor will scan forward from that
     * position to find a valid data item boundary before beginning iteration.
     *
     * @param path the path to the data file to read
     * @param metadata the file metadata providing the file index
     * @param dataType the type of data items in this file, used for boundary validation
     * @param startByte the starting byte offset in the file (will be adjusted to nearest boundary if non-zero)
     * @param endByte the ending byte offset in the file (exclusive)
     * @param bufferSizeBytes the buffer size for both boundary scanning and stream reading
     * @param totalBoundarySearchTime atomic counter to accumulate boundary search time in milliseconds
     * @throws IOException if there was a problem opening the file or finding a valid boundary
     */
    public ChunkedFileIterator(
            @NonNull final Path path,
            @NonNull final DataFileMetadata metadata,
            @NonNull final Type dataType,
            long startByte,
            long endByte,
            int bufferSizeBytes,
            @NonNull final AtomicLong totalBoundarySearchTime)
            throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            this.metadata = metadata;

            this.startByte = startByte;
            this.endByte = endByte;

            this.dataType = dataType;

            this.bufferSizeBytes = bufferSizeBytes;

            if (startByte > 0) {
                // Find boundary, then adjust startByte
                final long startTime = System.currentTimeMillis();
                this.startByte += findBoundaryOffset();
                totalBoundarySearchTime.addAndGet(System.currentTimeMillis() - startTime);
            }

            // Position channel and open streams
            channel.position(this.startByte);
            openStreams();
        } catch (final Exception e) {
            // Ensure channel is closed if constructor fails after opening
            try {
                channel.close();
            } catch (final IOException closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        }
    }

    /**
     * Advance to the next data item within this chunk's byte range.
     *
     * @return true if a data item was read, or false if the end of the chunk has been reached
     * @throws IOException if there was a problem reading from the file
     * @throws IllegalStateException if the iterator has been closed
     * @throws IllegalArgumentException if an unknown data file field is encountered
     */
    public boolean next() throws IOException {
        if (closed) {
            throw new IllegalStateException("Cannot read from a closed iterator");
        }

        while (in.hasRemaining()) {
            currentDataItemFilePosition = startByte + in.position();

            if (currentDataItemFilePosition >= endByte) {
                return false;
            }

            final int tag = in.readVarInt(false);
            final int fieldNum = tag >> TAG_FIELD_OFFSET;

            if (fieldNum == FIELD_DATAFILE_ITEMS.number()) {
                final int dataItemSize = in.readVarInt(false);
                dataItemBuffer = fillBuffer(dataItemSize);
                return true;
            } else if (fieldNum == FIELD_DATAFILE_METADATA.number()) {
                final int metadataSize = in.readVarInt(false);
                in.skip(metadataSize);
            } else {
                throw new IllegalArgumentException("Unknown data file field: " + tag);
            }
        }

        return false;
    }

    /**
     * Get the current data item's data. This is a shared buffer and must NOT be leaked from
     * the call site or modified directly.
     *
     * @return buffer containing the data item bytes, or null if the iterator has been closed
     *         or is in the before-first or after-last states
     */
    public BufferedData getDataItemData() {
        return dataItemBuffer;
    }

    /**
     * Get the data location (file index + byte offset) for the current data item.
     *
     * @return current data item location encoded as a long value
     */
    public long getDataItemDataLocation() {
        return DataFileCommon.dataLocation(metadata.getIndex(), currentDataItemFilePosition);
    }

    /**
     * Close the iterator, releasing all resources including the file channel and streams.
     *
     * @throws IOException if this resource cannot be closed
     */
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            dataItemBuffer = null;
            if (bufferedInputStream != null) {
                bufferedInputStream.close();
            }
            channel.close();
        }
    }

    // =================================================================================================================
    // Private methods

    /**
     * Opens buffered input streams on top of the file channel for sequential reading.
     */
    private void openStreams() {
        final var channelStream = Channels.newInputStream(channel);
        this.bufferedInputStream = new BufferedInputStream(channelStream, bufferSizeBytes);
        this.in = new ReadableStreamingData(bufferedInputStream);
    }

    /**
     * Scans forward from the current {@code startByte} position to find the offset to the nearest
     * valid data item boundary. Uses buffered reads to minimize disk I/O.
     *
     * <p>The method reads a chunk of data and scans byte-by-byte looking for a valid protobuf tag
     * followed by data that can be successfully parsed according to the {@code dataType}.
     *
     * @return the offset from {@code startByte} to the nearest valid data item boundary
     * @throws IOException if no valid boundary is found within the buffer or if reading fails
     */
    private long findBoundaryOffset() throws IOException {
        // Use buffer to minimize disk I/O and channel repositioning
        // It should account for boundary + full data item to validate its proto schema
        final ByteBuffer scanBuffer = ByteBuffer.allocate(bufferSizeBytes);

        // Read large chunk at current position
        int bytesRead = MerkleDbFileUtils.completelyRead(channel, scanBuffer, startByte);
        if (bytesRead <= 0) {
            throw new IOException("No valid data item boundary found in chunk");
        }

        scanBuffer.flip();
        final BufferedData bufferData = BufferedData.wrap(scanBuffer);

        // Scan through buffer looking for valid boundary
        while (bufferData.hasRemaining()) {
            final long positionInBuffer = bufferData.position();

            try {
                final int tag = bufferData.readVarInt(false);
                final int fieldNum = tag >> TAG_FIELD_OFFSET;

                if ((fieldNum == FIELD_DATAFILE_ITEMS.number())
                        && ((tag & ProtoConstants.TAG_WIRE_TYPE_MASK)
                                == ProtoConstants.WIRE_TYPE_DELIMITED.ordinal())) {
                    final int dataItemSize = bufferData.readVarInt(false);
                    final long dataStartPosition = bufferData.position();

                    if (dataItemSize > 0 && (dataStartPosition + dataItemSize <= bufferData.limit())) {
                        bufferData.limit(dataStartPosition + dataItemSize);

                        if (isValidDataItem(bufferData)) {
                            return positionInBuffer;
                        }

                        bufferData.limit(bytesRead);
                    }
                }

                // Not found, advance by 1 byte
                bufferData.position(positionInBuffer + 1);
            } catch (final Exception e) {
                // Parsing failed, advance by 1 byte
                bufferData.position(positionInBuffer + 1);
            }
        }

        throw new IOException("No valid data item boundary found in chunk");
    }

    /**
     * Validates whether the buffer contains a valid data item of the expected type.
     *
     * @param buffer the buffer containing potential data item bytes
     * @return true if the buffer contains valid data that can be parsed, false otherwise
     */
    private boolean isValidDataItem(@NonNull final BufferedData buffer) {
        try {
            if (!buffer.hasRemaining()) {
                return false;
            }

            return switch (dataType) {
                // Parsing without exception means valid data
                case P2H -> validateVirtualHashRecord(buffer);
                case P2KV -> validateVirtualLeafBytes(buffer);
                case K2P -> validateBucket(buffer);
                default -> false;
            };

        } catch (final Exception e) {
            // Any parsing exception means invalid data
            return false;
        }
    }

    /**
     * Attempts to parse the buffer as a {@link VirtualHashRecord}.
     *
     * @param buffer the buffer containing potential hash record bytes
     * @return true if parsing succeeds
     */
    private boolean validateVirtualHashRecord(@NonNull final BufferedData buffer) {
        VirtualHashRecord.parseFrom(buffer);
        return true;
    }

    /**
     * Attempts to parse the buffer as a {@link VirtualLeafBytes}.
     *
     * @param buffer the buffer containing potential leaf bytes
     * @return true if parsing succeeds
     */
    private boolean validateVirtualLeafBytes(@NonNull final BufferedData buffer) {
        VirtualLeafBytes.parseFrom(buffer);
        return true;
    }

    /**
     * Attempts to parse the buffer as a {@link Bucket}.
     *
     * @param buffer the buffer containing potential bucket bytes
     * @return true if parsing succeeds
     */
    private boolean validateBucket(@NonNull final BufferedData buffer) throws IOException {
        try (final Bucket bucket = new ParsedBucket()) {
            bucket.readFrom(buffer);
            return true;
        }
    }

    /**
     * Reads the specified number of bytes from the current position into a buffer.
     *
     * @param bytesToRead number of bytes to read
     * @return buffer containing the requested bytes
     * @throws IOException if the requested bytes cannot be read or if bytesToRead is invalid
     */
    private BufferedData fillBuffer(int bytesToRead) throws IOException {
        if (bytesToRead <= 0) {
            throw new IOException("Malformed data, requested bytes: " + bytesToRead);
        }

        // Create or resize the buffer if necessary
        if (dataItemBuffer == null || dataItemBuffer.capacity() < bytesToRead) {
            dataItemBuffer = BufferedData.allocate(bytesToRead);
        }

        dataItemBuffer.position(0);
        dataItemBuffer.limit(bytesToRead);
        final long bytesRead = in.readBytes(dataItemBuffer);
        if (bytesRead != bytesToRead) {
            throw new IOException("Couldn't read " + bytesToRead + " bytes, only read " + bytesRead);
        }

        dataItemBuffer.position(0);
        return dataItemBuffer;
    }
}
