// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.pipeline;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_ITEMS;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_METADATA;

import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.statevalidation.poc.model.ItemData;
import com.hedera.statevalidation.poc.model.ItemData.Type;
import com.swirlds.merkledb.files.DataFileCommon;
import com.swirlds.merkledb.files.DataFileMetadata;
import com.swirlds.merkledb.files.hashmap.Bucket;
import com.swirlds.merkledb.files.hashmap.ParsedBucket;
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

public class ChunkedFileIterator implements AutoCloseable {
    // move to cmd params?
    private static final int BUFFER_SIZE = 128 * 1024;

    private final FileChannel channel;
    private final DataFileMetadata metadata;

    private long startByte;
    private final long endByte;

    private final ItemData.Type dataType;

    private BufferedInputStream bufferedInputStream;
    private ReadableSequentialData in;
    private BufferedData dataItemBuffer;
    private long currentDataItemFilePosition;
    private boolean closed = false;

    public ChunkedFileIterator(
            @NonNull final Path path,
            @NonNull final DataFileMetadata metadata,
            @NonNull final Type dataType,
            long startByte,
            long endByte,
            @NonNull final AtomicLong totalBoundarySearchMillis)
            throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            this.metadata = metadata;

            this.startByte = startByte;
            this.endByte = endByte;

            this.dataType = dataType;

            if (startByte > 0) {
                // Find boundary, then position channel and open streams
                final long startTime = System.currentTimeMillis();
                this.startByte += findBoundaryOffset();
                // FIXME: update to nanos
                final long boundaryOffsetSearchTime = System.currentTimeMillis() - startTime;
                //            System.out.println("Found boundary offset in:" + boundaryOffsetSearchTime + " ms");
                totalBoundarySearchMillis.addAndGet(boundaryOffsetSearchTime);
                channel.position(this.startByte);
                openStreams();
            } else {
                // At file start
                channel.position(startByte);
                openStreams();
            }
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

    private void openStreams() {
        final var channelStream = Channels.newInputStream(channel);
        this.bufferedInputStream = new BufferedInputStream(channelStream, BUFFER_SIZE);
        this.in = new ReadableStreamingData(bufferedInputStream);
    }

    private long findBoundaryOffset() throws IOException {
        // Use buffer to minimize disk I/O and channel repositioning
        // It should account for boundary + full data item to validate its proto schema
        final ByteBuffer scanBuffer = ByteBuffer.allocate(BUFFER_SIZE);

        // Read large chunk at current position
        scanBuffer.clear();
        channel.position(startByte);
        int bytesRead = channel.read(scanBuffer);
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
                        final long savedPos = bufferData.position();

                        if (isValidDataItem(bufferData)) {
                            return positionInBuffer;
                        }

                        bufferData.position(savedPos);
                        bufferData.limit(bytesRead);
                    }
                }

                // Not found, advance by 1 byte
                bufferData.position(positionInBuffer + 1);
            } catch (Exception e) {
                // Parsing failed, advance by 1 byte
                bufferData.position(positionInBuffer + 1);
            }
        }

        throw new IOException("No valid data item boundary found in chunk");
    }

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

    private boolean validateVirtualHashRecord(@NonNull final BufferedData buffer) {
        VirtualHashRecord.parseFrom(buffer);
        return true;
    }

    private boolean validateVirtualLeafBytes(@NonNull final BufferedData buffer) {
        VirtualLeafBytes.parseFrom(buffer);
        return true;
    }

    private boolean validateBucket(@NonNull final BufferedData buffer) {
        final Bucket bucket = new ParsedBucket();
        bucket.readFrom(buffer);
        return true;
    }

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
                throw new IllegalArgumentException("Unknown data file field: " + fieldNum);
            }
        }

        return false;
    }

    public BufferedData getDataItemData() {
        return dataItemBuffer;
    }

    public long getDataItemDataLocation() {
        return DataFileCommon.dataLocation(metadata.getIndex(), currentDataItemFilePosition);
    }

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

    private BufferedData fillBuffer(int bytesToRead) throws IOException {
        if (bytesToRead <= 0) {
            throw new IOException("Malformed data, requested bytes: " + bytesToRead);
        }

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
