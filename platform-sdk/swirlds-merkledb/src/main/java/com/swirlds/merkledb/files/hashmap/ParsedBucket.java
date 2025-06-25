// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files.hashmap;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.merkledb.files.hashmap.HalfDiskHashMap.INVALID_VALUE;

import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 *
 */
public final class ParsedBucket extends Bucket {

    private static final Logger logger = LogManager.getLogger(ParsedBucket.class);

    /** Bucket index */
    private int bucketIndex = 0;

    /** List of bucket entries in this bucket */
    private final List<BucketEntry> entries = new ArrayList<>(64);

    /**
     * Create a new bucket with the default size.
     */
    public ParsedBucket() {
        this(null);
    }

    /**
     * Create a new bucket with the default size.
     */
    ParsedBucket(final ReusableBucketPool bucketPool) {
        super(bucketPool);
    }

    /**
     * Reset for next use
     */
    public void clear() {
        bucketIndex = 0;
        if (entries != null) {
            entries.clear();
        }
    }

    /** Get the index for this bucket */
    public int getBucketIndex() {
        return bucketIndex;
    }

    /** Set the index for this bucket */
    public void setBucketIndex(int index) {
        bucketIndex = index;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Get the number of entries stored in this bucket */
    public int getBucketEntryCount() {
        return entries.size();
    }

    /** Get the size of this bucket in bytes, including header */
    public int sizeInBytes() {
        int size = 0;
        // Include bucket index even if it has default value (zero)
        size += ProtoWriterTools.sizeOfTag(FIELD_BUCKET_INDEX, ProtoConstants.WIRE_TYPE_FIXED_32_BIT) + Integer.BYTES;
        for (final BucketEntry entry : entries) {
            size += ProtoWriterTools.sizeOfDelimited(FIELD_BUCKET_ENTRIES, entry.sizeInBytes());
        }
        return size;
    }

    /**
     * Find a value for given key
     *
     * @param keyHashCode the int hash for the key
     * @param key the key object
     * @param notFoundValue the long to return if the key is not found
     * @return the stored value for given key or notFoundValue if nothing is stored for the key
     * @throws IOException If there was a problem reading the value from file
     */
    public long findValue(final int keyHashCode, final Bytes key, final long notFoundValue) throws IOException {
        final int entryIndex = findEntryIndex(keyHashCode, key);
        if (entryIndex >= 0) {
            // yay! we found it
            return entries.get(entryIndex).getValue();
        } else {
            return notFoundValue;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void putValue(final Bytes keyBytes, final int keyHashCode, final long oldValue, final long value) {
        final boolean needCheckOldValue = oldValue != INVALID_VALUE;
        try {
            final int entryIndex = findEntryIndex(keyHashCode, keyBytes);
            if (value == INVALID_VALUE) {
                if (entryIndex >= 0) { // if found
                    final BucketEntry entry = entries.get(entryIndex);
                    if (needCheckOldValue && (oldValue != entry.getValue())) {
                        return;
                    }
                    entries.remove(entryIndex);
                } else {
                    // entry not found, nothing to delete
                }
                return;
            }
            if (entryIndex >= 0) {
                // yay! we found it, so update value
                final BucketEntry entry = entries.get(entryIndex);
                if (needCheckOldValue && (oldValue != entry.getValue())) {
                    return;
                }
                entry.setValue(value);
            } else {
                if (needCheckOldValue) {
                    return;
                }
                final BucketEntry newEntry = new BucketEntry(keyHashCode, value, keyBytes);
                entries.add(newEntry);
                checkLargestBucket(entries.size());
            }
        } catch (IOException e) {
            logger.error(EXCEPTION.getMarker(), "Failed putting key={} value={} in a bucket", keyBytes, value, e);
            throw new UncheckedIOException(e);
        }
    }

    public void forEachEntry(final Consumer<BucketEntry> consumer) {
        entries.forEach(consumer);
    }

    public void readFrom(final ReadableSequentialData in) {
        // defaults
        bucketIndex = 0;
        entries.clear();

        int entriesCount = 0;
        while (in.hasRemaining()) {
            final int tag = in.readVarInt(false);
            final int fieldNum = tag >> TAG_FIELD_OFFSET;
            if (fieldNum == FIELD_BUCKET_INDEX.number()) {
                bucketIndex = in.readInt();
            } else if (fieldNum == FIELD_BUCKET_ENTRIES.number()) {
                final int entryBytesSize = in.readVarInt(false);
                final long oldLimit = in.limit();
                in.limit(in.position() + entryBytesSize);
                entries.add(new BucketEntry(in));
                in.limit(oldLimit);
                entriesCount++;
            } else {
                throw new IllegalArgumentException("Unknown bucket field: " + fieldNum);
            }
        }

        checkLargestBucket(entriesCount);
    }

    public void writeTo(final WritableSequentialData out) {
        // Bucket index is not optional, write the value even if default (zero)
        ProtoWriterTools.writeTag(out, FIELD_BUCKET_INDEX);
        out.writeInt(bucketIndex);
        for (final BucketEntry entry : entries) {
            ProtoWriterTools.writeTag(out, FIELD_BUCKET_ENTRIES);
            out.writeVarInt(entry.sizeInBytes(), false);
            entry.writeTo(out);
        }
    }

    // =================================================================================================================
    // Private API

    private int findEntryIndex(final int keyHashCode, final Bytes keyBytes) throws IOException {
        final int entryCount = entries.size();
        for (int index = 0; index < entryCount; index++) {
            final BucketEntry entry = entries.get(index);
            if (keyHashCode == entry.getHashCode()) {
                if (entry.getKeyBytes().equals(keyBytes)) {
                    return index;
                }
            }
        }
        return -1;
    }

    /** toString for debugging */
    @SuppressWarnings("StringConcatenationInsideStringBufferAppend")
    @Override
    public String toString() {
        final int entryCount = getBucketEntryCount();
        final int size = sizeInBytes();
        final StringBuilder sb = new StringBuilder("ParsedBucket{bucketIndex=" + getBucketIndex() + ", entryCount="
                + entryCount + ", size=" + size + "\n");
        for (int i = 0; i < entryCount; i++) {
            final BucketEntry entry = entries.get(i);
            final int hashCode = entry.getHashCode();
            final long value = entry.getValue();
            final Bytes keyBytes = entry.getKeyBytes();
            sb.append("    ENTRY[" + i + "] value= " + value + " keyHashCode=" + hashCode + " key=" + keyBytes + "\n");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * A single entry in a bucket, which contains key hash code, value (usually, path), and full
     * serialized key. A bucket may contain multiple such entries.
     *
     * <p>This class would be a record, if it was immutable. However, when a value is updated
     * in a bucket, and a bucket entry already exists for the same key, instead of creating a new
     * entry, we just update the value in the existing entry.
     */
    public static class BucketEntry {

        /** Key hash code */
        private final int hashCode;
        /** Long value. May be updated */
        private long value;
        /** Key */
        private final Bytes keyBytes;

        /** Creates new bucket entry from hash code, value, and serialized key bytes */
        public BucketEntry(final int hashCode, final long value, @NonNull final Bytes keyBytes) {
            this.hashCode = hashCode;
            this.value = value;
            this.keyBytes = keyBytes;
        }

        /** Creates new bucket entry by reading its fields from the given protobuf buffer */
        public BucketEntry(final ReadableSequentialData entryData) {
            // defaults
            int hashCode = 0;
            long value = 0;
            Bytes keyBytes = null;

            // read fields
            while (entryData.hasRemaining()) {
                final int tag = entryData.readVarInt(false);
                final int fieldNum = tag >> TAG_FIELD_OFFSET;
                if (fieldNum == Bucket.FIELD_BUCKETENTRY_HASHCODE.number()) {
                    hashCode = entryData.readInt();
                } else if (fieldNum == Bucket.FIELD_BUCKETENTRY_VALUE.number()) {
                    value = entryData.readLong();
                } else if (fieldNum == Bucket.FIELD_BUCKETENTRY_KEYBYTES.number()) {
                    final int bytesSize = entryData.readVarInt(false);
                    keyBytes = entryData.readBytes(bytesSize);
                } else {
                    throw new IllegalArgumentException("Unknown bucket entry field: " + fieldNum);
                }
            }

            // check required fields
            if (keyBytes == null) {
                throw new IllegalArgumentException("Null key for bucket entry");
            }

            this.hashCode = hashCode;
            this.value = value;
            this.keyBytes = keyBytes;
        }

        public int getHashCode() {
            return hashCode;
        }

        public long getValue() {
            return value;
        }

        public void setValue(long value) {
            this.value = value;
        }

        public Bytes getKeyBytes() {
            return keyBytes;
        }

        public int sizeInBytes() {
            int size = 0;
            size += ProtoWriterTools.sizeOfTag(Bucket.FIELD_BUCKETENTRY_HASHCODE, ProtoConstants.WIRE_TYPE_FIXED_32_BIT)
                    + Integer.BYTES;
            size += ProtoWriterTools.sizeOfTag(Bucket.FIELD_BUCKETENTRY_VALUE, ProtoConstants.WIRE_TYPE_FIXED_64_BIT)
                    + Long.BYTES;
            size += ProtoWriterTools.sizeOfDelimited(
                    Bucket.FIELD_BUCKETENTRY_KEYBYTES, Math.toIntExact(keyBytes.length()));
            return size;
        }

        public void writeTo(final WritableSequentialData out) {
            ProtoWriterTools.writeTag(out, Bucket.FIELD_BUCKETENTRY_HASHCODE);
            out.writeInt(hashCode);
            ProtoWriterTools.writeTag(out, Bucket.FIELD_BUCKETENTRY_VALUE);
            out.writeLong(value);
            ProtoWriterTools.writeDelimited(
                    out, Bucket.FIELD_BUCKETENTRY_KEYBYTES, Math.toIntExact(keyBytes.length()), keyBytes::writeTo);
        }
    }
}
