// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILEMETADATA_COMPACTION_LEVEL;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILEMETADATA_CREATION_NANOS;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILEMETADATA_CREATION_SECONDS;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILEMETADATA_INDEX;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILEMETADATA_ITEMS_COUNT;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILEMETADATA_ITEM_VERSION;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_ITEMS;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_METADATA;

import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.base.utility.ToStringBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * DataFile's metadata that is stored in the data file's footer
 */
public final class DataFileMetadata {

    /**
     * Maximum level of compaction for storage files.
     */
    public static final int MAX_COMPACTION_LEVEL = 127;

    /** The file index, in a data file collection */
    private final int index;

    /** The creation date of this file */
    private final Instant creationDate;

    /**
     * The number of data items the file contains. When metadata is loaded from a file, the number
     * of items is read directly from there. When metadata is created by {@link DataFileWriter} for
     * new files during flushes or compactions, this field is set to 0 initially and then updated
     * right before the file is finished writing. For such new files, no code needs their metadata
     * until they are fully written, so wrong (zero) item count shouldn't be an issue.
     */
    private volatile long itemsCount;

    /** The level of compaction this file has. See {@link DataFileCompactor}*/
    private final byte compactionLevel;

    // Set in writeTo()
    private long dataItemCountHeaderOffset = 0;

    /**
     * Create a new DataFileMetadata with complete set of data
     *
     * @param itemsCount The number of data items the file contains
     * @param index The file index, in a data file collection
     * @param creationDate The creation data of this file, this is critical as it is used when
     *     merging two files to know which files data is newer.
     */
    public DataFileMetadata(
            final long itemsCount, final int index, final Instant creationDate, final int compactionLevel) {
        this.itemsCount = itemsCount;
        this.index = index;
        this.creationDate = creationDate;
        assert compactionLevel >= 0 && compactionLevel < MAX_COMPACTION_LEVEL;
        this.compactionLevel = (byte) compactionLevel;
    }

    /**
     * Create a DataFileMetadata loading it from a existing file
     *
     * @param file The file to read metadata from
     * @throws IOException If there was a problem reading metadata footer from the file
     */
    public DataFileMetadata(Path file) throws IOException {
        // Defaults
        int index = 0;
        long creationSeconds = 0;
        int creationNanos = 0;
        long itemsCount = 0;
        byte compactionLevel = 0;

        // Read values from the file, skipping all data items
        try (final ReadableStreamingData in = new ReadableStreamingData(file)) {
            while (in.hasRemaining()) {
                final int tag = in.readVarInt(false);
                final int fieldNum = tag >> TAG_FIELD_OFFSET;
                if (fieldNum == FIELD_DATAFILE_METADATA.number()) {
                    final int metadataSize = in.readVarInt(false);
                    final long oldLimit = in.limit();
                    in.limit(in.position() + metadataSize);
                    try {
                        while (in.hasRemaining()) {
                            final int metadataTag = in.readVarInt(false);
                            final int metadataFieldNum = metadataTag >> TAG_FIELD_OFFSET;
                            if (metadataFieldNum == FIELD_DATAFILEMETADATA_INDEX.number()) {
                                index = in.readVarInt(false);
                            } else if (metadataFieldNum == FIELD_DATAFILEMETADATA_CREATION_SECONDS.number()) {
                                creationSeconds = in.readVarLong(false);
                            } else if (metadataFieldNum == FIELD_DATAFILEMETADATA_CREATION_NANOS.number()) {
                                creationNanos = in.readVarInt(false);
                            } else if (metadataFieldNum == FIELD_DATAFILEMETADATA_ITEMS_COUNT.number()) {
                                itemsCount = in.readLong();
                            } else if (metadataFieldNum == FIELD_DATAFILEMETADATA_ITEM_VERSION.number()) {
                                in.readVarLong(false); // this field is no longer used
                            } else if (metadataFieldNum == FIELD_DATAFILEMETADATA_COMPACTION_LEVEL.number()) {
                                final int compactionLevelInt = in.readVarInt(false);
                                assert compactionLevelInt < MAX_COMPACTION_LEVEL;
                                compactionLevel = (byte) compactionLevelInt;
                            } else {
                                throw new IllegalArgumentException(
                                        "Unknown data file metadata field: " + metadataFieldNum);
                            }
                        }
                    } finally {
                        in.limit(oldLimit);
                    }
                    break;
                } else if (fieldNum == FIELD_DATAFILE_ITEMS.number()) {
                    // Just skip it. By default, metadata is written to the very beginning of the file,
                    // so this code should never be executed. However, with other implementations data
                    // items may come first, this code must be ready to handle it
                    final int size = in.readVarInt(false);
                    in.skip(size);
                } else {
                    throw new IllegalArgumentException("Unknown data file field: " + fieldNum);
                }
            }
        }

        // Initialize this object
        this.index = index;
        this.creationDate = Instant.ofEpochSecond(creationSeconds, creationNanos);
        this.itemsCount = itemsCount;
        this.compactionLevel = compactionLevel;
    }

    void writeTo(final BufferedData out) {
        ProtoWriterTools.writeDelimited(out, FIELD_DATAFILE_METADATA, fieldsSizeInBytes(), this::writeFields);
    }

    private void writeFields(final WritableSequentialData out) {
        if (getIndex() != 0) {
            ProtoWriterTools.writeTag(out, FIELD_DATAFILEMETADATA_INDEX);
            out.writeVarInt(getIndex(), false);
        }
        final Instant creationInstant = getCreationDate();
        ProtoWriterTools.writeTag(out, FIELD_DATAFILEMETADATA_CREATION_SECONDS);
        out.writeVarLong(creationInstant.getEpochSecond(), false);
        ProtoWriterTools.writeTag(out, FIELD_DATAFILEMETADATA_CREATION_NANOS);
        out.writeVarInt(creationInstant.getNano(), false);
        dataItemCountHeaderOffset = out.position();
        ProtoWriterTools.writeTag(out, FIELD_DATAFILEMETADATA_ITEMS_COUNT);
        out.writeLong(0); // will be updated later
        if (getCompactionLevel() != 0) {
            ProtoWriterTools.writeTag(out, FIELD_DATAFILEMETADATA_COMPACTION_LEVEL);
            out.writeVarInt(compactionLevel, false);
        }
    }

    /**
     * Get the number of data items the file contains. If this method is called before the
     * corresponding file is completely written by {@link DataFileWriter}, the return value is 0.
     */
    public long getDataItemCount() {
        return itemsCount;
    }

    /**
     * Updates number of data items in the file. This method must be called after metadata is
     * written to a file using {@link #writeTo(BufferedData)}.
     *
     * <p>This method is called by {@link DataFileWriter} right before the file is finished writing.
     */
    void updateDataItemCount(final BufferedData out, final long count) {
        this.itemsCount = count;
        assert dataItemCountHeaderOffset != 0;
        out.position(dataItemCountHeaderOffset);
        ProtoWriterTools.writeTag(out, FIELD_DATAFILEMETADATA_ITEMS_COUNT);
        out.writeLong(count);
    }

    /** Get the files index, out of a set of data files */
    public int getIndex() {
        return index;
    }

    /** Get the date the file was created in UTC */
    public Instant getCreationDate() {
        return creationDate;
    }

    // For testing purposes. In low-level data file tests, skip this number of bytes from the
    // beginning of the file before reading data items, assuming file metadata is always written
    // first, then data items
    int metadataSizeInBytes() {
        return ProtoWriterTools.sizeOfDelimited(FIELD_DATAFILE_METADATA, fieldsSizeInBytes());
    }

    private int fieldsSizeInBytes() {
        int size = 0;
        if (index != 0) {
            size += ProtoWriterTools.sizeOfTag(FIELD_DATAFILEMETADATA_INDEX, ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG);
            size += ProtoWriterTools.sizeOfVarInt32(index);
        }
        size += ProtoWriterTools.sizeOfTag(
                FIELD_DATAFILEMETADATA_CREATION_SECONDS, ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG);
        size += ProtoWriterTools.sizeOfVarInt64(creationDate.getEpochSecond());
        size += ProtoWriterTools.sizeOfTag(
                FIELD_DATAFILEMETADATA_CREATION_NANOS, ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG);
        size += ProtoWriterTools.sizeOfVarInt64(creationDate.getNano());
        size += ProtoWriterTools.sizeOfTag(FIELD_DATAFILEMETADATA_ITEMS_COUNT, ProtoConstants.WIRE_TYPE_FIXED_64_BIT);
        size += Long.BYTES;
        if (compactionLevel != 0) {
            size += ProtoWriterTools.sizeOfTag(
                    FIELD_DATAFILEMETADATA_COMPACTION_LEVEL, ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG);
            size += ProtoWriterTools.sizeOfVarInt32(compactionLevel);
        }
        return size;
    }

    public int getCompactionLevel() {
        return compactionLevel;
    }

    /** toString for debugging */
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("itemsCount", itemsCount)
                .append("index", index)
                .append("creationDate", creationDate)
                .toString();
    }

    /**
     * Equals for use when comparing in collections, based on all fields in the toString() output.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DataFileMetadata that = (DataFileMetadata) o;
        return itemsCount == that.itemsCount
                && index == that.index
                && compactionLevel == that.compactionLevel
                && Objects.equals(this.creationDate, that.creationDate);
    }

    /**
     * hashCode for use when comparing in collections, based on all fields in the toString() output.
     */
    @Override
    public int hashCode() {
        return Objects.hash(itemsCount, index, creationDate, compactionLevel);
    }
}
