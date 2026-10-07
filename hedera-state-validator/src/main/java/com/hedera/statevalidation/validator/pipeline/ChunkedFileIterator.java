// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.pipeline;

import static com.hedera.pbj.runtime.ProtoConstants.WIRE_TYPE_DELIMITED;
import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_ITEMS;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_METADATA;

import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.ProtoWriterTools;
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
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
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
 * Supported data types for boundary validation include {@link VirtualHashChunk},
 * {@link VirtualLeafBytes}, and {@link Bucket}.
 *
 * <p>Each iterator instance should be used from a single thread, but multiple instances
 * can safely operate on different byte ranges of the same file in parallel.
 *
 * @see com.swirlds.merkledb.files.DataFileIterator
 * @see com.swirlds.merkledb.files.DataFileReader
 */
public class ChunkedFileIterator implements AutoCloseable {
    /** Data item tag. It's less than 128, so its varint encoding is a single byte */
    private static final int DATA_ITEM_TAG =
            (FIELD_DATAFILE_ITEMS.number() << TAG_FIELD_OFFSET) | WIRE_TYPE_DELIMITED.ordinal();
    /** Max data item header size: the data item tag and a varint data item size */
    private static final int MAX_DATA_ITEM_HEADER_SIZE =
            ProtoWriterTools.sizeOfTag(FIELD_DATAFILE_ITEMS, WIRE_TYPE_DELIMITED)
                    + ProtoWriterTools.sizeOfUnsignedVarInt32(Integer.MAX_VALUE);

    /** File channel used for reading the data file and positioning within the byte range */
    private final FileChannel channel;
    /** The file metadata providing file index for data location calculation */
    private final DataFileMetadata metadata;

    private final int hashChunkHeight;

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
     * position to find a valid data item boundary before beginning iteration. If no data item
     * starts before {@code endByte}, the iterator is empty.
     *
     * @param path the path to the data file to read
     * @param metadata the file metadata providing the file index
     * @param hashChunkHeight the hash chunk height, used for hash chunk validation via its deserialization
     * @param dataType the type of data items in this file, used for boundary validation
     * @param startByte the starting byte offset in the file (will be adjusted to nearest boundary if non-zero)
     * @param endByte the ending byte offset in the file (exclusive)
     * @param bufferSizeBytes the buffer size for both boundary scanning and stream reading
     * @param totalBoundarySearchTime atomic counter to accumulate boundary search time in milliseconds
     * @throws IOException if there was a problem opening or reading the file
     */
    public ChunkedFileIterator(
            @NonNull final Path path,
            @NonNull final DataFileMetadata metadata,
            int hashChunkHeight,
            @NonNull final Type dataType,
            long startByte,
            long endByte,
            int bufferSizeBytes,
            @NonNull final AtomicLong totalBoundarySearchTime)
            throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            this.metadata = metadata;
            this.hashChunkHeight = hashChunkHeight;

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

            final int fieldNum = ProtoParserTools.readNextFieldNumber(in);

            if (fieldNum == FIELD_DATAFILE_ITEMS.number()) {
                final int dataItemSize = in.readVarInt(false);
                dataItemBuffer = fillBuffer(dataItemSize);
                return true;
            } else if (fieldNum == FIELD_DATAFILE_METADATA.number()) {
                ProtoParserTools.skipField(in, WIRE_TYPE_DELIMITED);
            } else {
                throw new IllegalArgumentException("Unknown data file field: " + fieldNum);
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
     * <p>The method reads the file in windows of {@code bufferSizeBytes} and scans byte-by-byte looking
     * for a valid data item header followed by data that can be successfully parsed according to the
     * {@code dataType}. The scan continues window by window until a boundary is found or {@code endByte}
     * is reached, so data items larger than the buffer are skipped over correctly.
     *
     * <p>If no data item starts within {@code [startByte, endByte)}, the whole range belongs to a data
     * item that starts in a preceding segment and is read by that segment's iterator. In this case the
     * returned offset points to {@code endByte}, which makes this iterator empty.
     *
     * @return the offset from {@code startByte} to the nearest valid data item boundary, or to
     *     {@code endByte} if no data item starts within this chunk
     * @throws IOException if reading fails
     */
    private long findBoundaryOffset() throws IOException {
        final long fileSize = channel.size();
        // Use buffer to minimize disk I/O and channel repositioning
        final ByteBuffer scanBuffer = ByteBuffer.allocate(bufferSizeBytes);
        final BufferedData bufferData = BufferedData.wrap(scanBuffer);

        long windowStart = startByte;
        while (windowStart < endByte) {
            scanBuffer.clear();
            final int bytesRead = MerkleDbFileUtils.completelyRead(channel, scanBuffer, windowStart);
            if (bytesRead <= 0) {
                break;
            }

            // Don't check candidates that may have their header cut by the end of the window, unless
            // the window reaches the end of the file. Such candidates are checked in the next window
            final boolean lastWindow = windowStart + bytesRead >= fileSize;
            final long scanLength =
                    Math.min(endByte - windowStart, lastWindow ? bytesRead : bytesRead - MAX_DATA_ITEM_HEADER_SIZE);

            for (long positionInBuffer = 0; positionInBuffer < scanLength; positionInBuffer++) {
                bufferData.limit(bytesRead);
                bufferData.position(positionInBuffer);
                if (isDataItemBoundary(bufferData, windowStart, fileSize)) {
                    return windowStart + positionInBuffer - startByte;
                }
            }

            windowStart += scanLength;
        }

        // No data item starts in this chunk, it's fully covered by a data item from a preceding chunk
        return endByte - startByte;
    }

    /**
     * Checks whether a valid data item of the expected type starts at the current position of the
     * scan buffer. On return, the buffer position and limit are undefined.
     *
     * <p>A candidate is accepted if it has the data item tag, its size fits into the file, it's
     * followed by another data item tag or the end of the file, and its data can be parsed. Data items
     * that don't fit into the scan buffer are parsed directly from the file.
     *
     * @param buffer the scan buffer, positioned at the candidate data item start
     * @param windowStart the file position of the start of the scan buffer
     * @param fileSize the file size
     * @return true if a valid data item starts at the current buffer position
     */
    private boolean isDataItemBoundary(
            @NonNull final BufferedData buffer, final long windowStart, final long fileSize) {
        try {
            if (buffer.readVarInt(false) != DATA_ITEM_TAG) {
                return false;
            }
            final int dataItemSize = buffer.readVarInt(false);
            if (dataItemSize <= 0) {
                return false;
            }
            final long dataStartInBuffer = buffer.position();
            final long dataFilePosition = windowStart + dataStartInBuffer;
            final long dataEndFilePosition = dataFilePosition + dataItemSize;
            if (dataEndFilePosition > fileSize) {
                return false;
            }
            // Cheap check before parsing: the next data item must start right after this one
            if (dataEndFilePosition < fileSize
                    && readByteAt(buffer, windowStart, dataEndFilePosition) != DATA_ITEM_TAG) {
                return false;
            }

            if (dataStartInBuffer + dataItemSize <= buffer.limit()) {
                buffer.limit(dataStartInBuffer + dataItemSize);
                return isValidDataItem(buffer);
            }
            // The data item doesn't fit into the scan buffer, parse it directly from the file. The stream
            // is not closed, as it would close the channel
            channel.position(dataFilePosition);
            final ReadableStreamingData dataItemIn = new ReadableStreamingData(
                    new BufferedInputStream(Channels.newInputStream(channel), bufferSizeBytes));
            dataItemIn.limit(dataItemSize);
            return isValidDataItem(dataItemIn);
        } catch (final Exception e) {
            // Parsing failed, not a boundary
            return false;
        }
    }

    /**
     * Reads a single byte at the given file position, using the scan buffer if it contains the position.
     *
     * @param buffer the scan buffer
     * @param windowStart the file position of the start of the scan buffer
     * @param filePosition the file position to read the byte at
     * @return the byte value
     * @throws IOException if the byte can't be read
     */
    private int readByteAt(@NonNull final BufferedData buffer, final long windowStart, final long filePosition)
            throws IOException {
        final long positionInBuffer = filePosition - windowStart;
        if (positionInBuffer < buffer.limit()) {
            return buffer.getByte(positionInBuffer) & 0xFF;
        }
        final ByteBuffer byteBuffer = ByteBuffer.allocate(1);
        if (MerkleDbFileUtils.completelyRead(channel, byteBuffer, filePosition) != 1) {
            throw new IOException("Failed to read a byte at position " + filePosition);
        }
        return byteBuffer.get(0) & 0xFF;
    }

    /**
     * Validates whether the input contains a valid data item of the expected type.
     *
     * @param in the input containing potential data item bytes, limited to the data item size
     * @return true if the input contains valid data that can be parsed, false otherwise
     */
    private boolean isValidDataItem(@NonNull final ReadableSequentialData in) {
        try {
            if (!in.hasRemaining()) {
                return false;
            }

            return switch (dataType) {
                // Parsing without exception means valid data
                case ID2C -> validateVirtualHashChunk(in);
                case P2KV -> validateVirtualLeafBytes(in);
                case K2P -> validateBucket(in);
                default -> false;
            };

        } catch (final Exception e) {
            // Any parsing exception means invalid data
            return false;
        }
    }

    /**
     * Attempts to parse the input as a {@link VirtualHashChunk}.
     *
     * @param in the input containing potential hash chunk bytes
     * @return true if parsing succeeds
     */
    private boolean validateVirtualHashChunk(@NonNull final ReadableSequentialData in) {
        VirtualHashChunk.parseFrom(in, hashChunkHeight);
        return true;
    }

    /**
     * Attempts to parse the input as a {@link VirtualLeafBytes}.
     *
     * @param in the input containing potential leaf bytes
     * @return true if parsing succeeds
     */
    private boolean validateVirtualLeafBytes(@NonNull final ReadableSequentialData in) {
        VirtualLeafBytes.parseFrom(in);
        return true;
    }

    /**
     * Attempts to parse the input as a {@link Bucket}.
     *
     * @param in the input containing potential bucket bytes
     * @return true if parsing succeeds
     */
    private boolean validateBucket(@NonNull final ReadableSequentialData in) throws IOException {
        try (final Bucket bucket = new ParsedBucket()) {
            bucket.readFrom(in);
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
