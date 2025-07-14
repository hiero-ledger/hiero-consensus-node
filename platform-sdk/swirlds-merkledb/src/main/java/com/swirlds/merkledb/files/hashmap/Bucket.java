// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files.hashmap;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.merkledb.files.hashmap.HalfDiskHashMap.INVALID_VALUE;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Class for accessing the data in a bucket. Each bucket has an index and contains a number
 * of bucket entries. Entries contain key hash codes (as a single bucket may contain keys
 * with different hash codes), values, and full serialized key bytes.
 *
 * <p>This class is not fully thread safe. Buckets may be updated in one thread and then
 * accessed from different threads, this use case is supported. However, buckets aren't
 * designed to be updated concurrently from multiple threads.
 *
 * <p>Protobuf schema:
 *
 * <pre>
 * message Bucket {
 *
 *     // Bucket index
 *     uint32 index = 1;
 *
 *     // Items
 *     repeated BucketEntry entries = 11;
 * }
 *
 * message BucketEntry {
 *
 *     // Key hash code
 *     int32 hashCode = 1;
 *
 *     // Entry value, e.g. path
 *     int64 value = 2;
 *
 *     // Serialized key
 *     bytes keyBytes = 3;
 * }
 * </pre>
 */
public sealed class Bucket implements Closeable permits ParsedBucket {

    private static final Logger logger = LogManager.getLogger(Bucket.class);

    /** Keep track of the bucket with most keys we have ever created for logging */
    private static final AtomicInteger LARGEST_BUCKET_CREATED = new AtomicInteger(0);

    protected static final FieldDefinition FIELD_BUCKET_INDEX =
            new FieldDefinition("index", FieldType.FIXED32, false, false, false, 1);
    protected static final FieldDefinition FIELD_BUCKET_ENTRIES =
            new FieldDefinition("entries", FieldType.MESSAGE, true, true, false, 11);

    protected static final FieldDefinition FIELD_BUCKETENTRY_HASHCODE =
            new FieldDefinition("hashCode", FieldType.FIXED32, false, false, false, 1);
    protected static final FieldDefinition FIELD_BUCKETENTRY_VALUE =
            new FieldDefinition("value", FieldType.FIXED64, false, false, false, 2);
    protected static final FieldDefinition FIELD_BUCKETENTRY_KEYBYTES =
            new FieldDefinition("keyBytes", FieldType.BYTES, false, false, false, 3);

    /** Size of FIELD_BUCKET_INDEX, in bytes. */
    private static final int METADATA_SIZE =
            ProtoWriterTools.sizeOfTag(FIELD_BUCKET_INDEX, ProtoConstants.WIRE_TYPE_FIXED_32_BIT) + Integer.BYTES;

    /**
     * Bucket pool this bucket is managed by, optional. If not null, the bucket is
     * released back to the pool on close.
     */
    protected final ReusableBucketPool bucketPool;

    private volatile BufferedData bucketData;

    private volatile long bucketIndexFieldOffset = 0;

    private int entryCount = 0;

    /**
     * Create a new bucket with the default size.
     */
    protected Bucket() {
        this(null);
    }

    /**
     * Create a new bucket with the default size.
     */
    protected Bucket(final ReusableBucketPool bucketPool) {
        this.bucketPool = bucketPool;
        this.bucketData = BufferedData.allocate(METADATA_SIZE);
        clear();
    }

    private void setSize(final int size, final boolean keepContent) {
        if (bucketData.capacity() < size) {
            final BufferedData newData = BufferedData.allocate(size);
            if (keepContent) {
                bucketData.resetPosition();
                newData.writeBytes(bucketData);
            }
            bucketData = newData;
        }
        bucketData.resetPosition();
        bucketData.limit(size);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        if (bucketPool != null) {
            bucketPool.releaseBucket(this);
        }
    }

    /**
     * Reset for next use.
     */
    public void clear() {
        setSize(METADATA_SIZE, false);
        bucketIndexFieldOffset = 0;
        setBucketIndex(0);
        entryCount = 0;
    }

    /** Get the index for this bucket */
    public int getBucketIndex() {
        final long bucketIndexValueOffset = bucketIndexFieldOffset
                + ProtoWriterTools.sizeOfTag(FIELD_BUCKET_INDEX, ProtoConstants.WIRE_TYPE_FIXED_32_BIT);
        return bucketData.getInt(bucketIndexValueOffset);
    }

    /** Set the index for this bucket */
    public void setBucketIndex(final int index) {
        bucketData.position(bucketIndexFieldOffset);
        ProtoWriterTools.writeTag(bucketData, FIELD_BUCKET_INDEX);
        bucketData.writeInt(index);
    }

    public boolean isEmpty() {
        return bucketData.length() == METADATA_SIZE;
    }

    /** Get the number of entries stored in this bucket */
    public int getBucketEntryCount() {
        return entryCount;
    }

    protected void checkLargestBucket(final int count) {
        if (!logger.isDebugEnabled(MERKLE_DB.getMarker())) {
            return;
        }
        if (count > LARGEST_BUCKET_CREATED.get()) {
            LARGEST_BUCKET_CREATED.set(count);
            logger.debug(MERKLE_DB.getMarker(), "New largest bucket, now = {} entries", count);
        }
    }

    /** Get the size of this bucket in bytes, including header */
    public int sizeInBytes() {
        return Math.toIntExact(bucketData.length());
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
        final FindResult result = findEntry(keyHashCode, key);
        if (result.found()) {
            // yay! we found it
            return result.entryValue();
        } else {
            return notFoundValue;
        }
    }

    /**
     * Put a key/value entry into this bucket.
     *
     * @param key the entry key
     * @param value the entry value, this can also be special
     *     HalfDiskHashMap.INVALID_VALUE to mean delete
     */
    public final void putValue(final Bytes key, final int keyHashCode, final long value) {
        putValue(key, keyHashCode, INVALID_VALUE, value);
    }

    /**
     * Optionally check the current value, and if it matches the given value, then put a
     * key/value entry into this bucket. If the existing value check is requested, but there
     * is no existing value for the key, the value is not added.
     *
     * @param key the entry key
     * @param keyHashCode the key hash code
     * @param oldValue the value to check the existing value against, if {@code checkOldValue} is true. If
     *                 {@code checkOldValue} is false, this old value is ignored
     * @param value the entry value, this can also be special
     *     HalfDiskHashMap.INVALID_VALUE to mean delete
     */
    public void putValue(final Bytes key, final int keyHashCode, final long oldValue, final long value) {
        final boolean needCheckOldValue = oldValue != INVALID_VALUE;
        final FindResult result = findEntry(keyHashCode, key);
        if (value == INVALID_VALUE) {
            if (result.found()) {
                if (needCheckOldValue && (oldValue != result.entryValue)) {
                    return;
                }
                final long nextEntryOffset = result.entryOffset() + result.entrySize();
                final long remainderSize = bucketData.length() - nextEntryOffset;
                if (remainderSize > 0) {
                    final BufferedData remainder = bucketData.slice(nextEntryOffset, remainderSize);
                    bucketData.position(result.entryOffset());
                    bucketData.writeBytes(remainder);
                }
                if (bucketIndexFieldOffset > result.entryOffset()) {
                    // It should not happen with default implementation, but if buckets are serialized
                    // using 3rd-party tools, field order may be arbitrary, and "bucket index" field
                    // may be after the deleted entry
                    bucketIndexFieldOffset -= result.entrySize();
                }
                bucketData.position(0); // limit() doesn't work if the new limit is less than the current pos
                bucketData.limit(result.entryOffset() + remainderSize);
                entryCount--;
            } else {
                // entry not found, nothing to delete
            }
            return;
        }
        if (result.found()) {
            // yay! we found it, so update value
            if (needCheckOldValue && (oldValue != result.entryValue)) {
                return;
            }
            bucketData.position(result.entryValueOffset());
            bucketData.writeLong(value);
        } else {
            if (needCheckOldValue) {
                return;
            }
            // add a new entry
            writeNewEntry(keyHashCode, value, key);
            checkLargestBucket(++entryCount);
        }
    }

    private void writeNewEntry(final int hashCode, final long value, final Bytes key) {
        final long entryOffset = bucketData.limit();
        final int keySize = Math.toIntExact(key.length());
        final int entrySize =
                ProtoWriterTools.sizeOfTag(FIELD_BUCKETENTRY_HASHCODE, ProtoConstants.WIRE_TYPE_FIXED_32_BIT)
                        + Integer.BYTES
                        + ProtoWriterTools.sizeOfTag(FIELD_BUCKETENTRY_VALUE, ProtoConstants.WIRE_TYPE_FIXED_64_BIT)
                        + Long.BYTES
                        + ProtoWriterTools.sizeOfDelimited(FIELD_BUCKETENTRY_KEYBYTES, keySize);
        final int totalSize = ProtoWriterTools.sizeOfDelimited(FIELD_BUCKET_ENTRIES, entrySize);
        setSize(Math.toIntExact(entryOffset + totalSize), true);
        bucketData.position(entryOffset);
        ProtoWriterTools.writeDelimited(bucketData, FIELD_BUCKET_ENTRIES, entrySize, out -> {
            ProtoWriterTools.writeTag(out, FIELD_BUCKETENTRY_HASHCODE);
            out.writeInt(hashCode);
            ProtoWriterTools.writeTag(out, FIELD_BUCKETENTRY_VALUE);
            out.writeLong(value);
            ProtoWriterTools.writeDelimited(out, FIELD_BUCKETENTRY_KEYBYTES, keySize, t -> t.writeBytes(key));
        });
    }

    public void readFrom(final ReadableSequentialData in) {
        final int size = Math.toIntExact(in.remaining());
        setSize(size, false);
        in.readBytes(bucketData);
        bucketData.flip();

        bucketIndexFieldOffset = 0;
        entryCount = 0;
        while (bucketData.hasRemaining()) {
            final long fieldOffset = bucketData.position();
            final int tag = bucketData.readVarInt(false);
            final int fieldNum = tag >> TAG_FIELD_OFFSET;
            if (fieldNum == FIELD_BUCKET_INDEX.number()) {
                bucketIndexFieldOffset = fieldOffset;
                bucketData.skip(Integer.BYTES);
            } else if (fieldNum == FIELD_BUCKET_ENTRIES.number()) {
                final int entryBytesSize = bucketData.readVarInt(false);
                bucketData.skip(entryBytesSize);
                entryCount++;
            } else {
                logger.error(
                        MERKLE_DB.getMarker(),
                        "Cannot read bucket: in={} in.pos={} off={} bd.pos={} bd.lim={} bd.data={}",
                        in,
                        in.position(),
                        fieldOffset,
                        bucketData.position(),
                        bucketData.limit(),
                        bucketData);
                throw new IllegalArgumentException("Unknown bucket field: " + fieldNum);
            }
        }

        checkLargestBucket(entryCount);
    }

    private static int readBucketEntryHashCode(final ReadableSequentialData in) {
        while (in.hasRemaining()) {
            final int tag = in.readVarInt(false);
            final int fieldNum = tag >> TAG_FIELD_OFFSET;
            if (fieldNum == FIELD_BUCKETENTRY_HASHCODE.number()) {
                return in.readInt();
            } else if (fieldNum == FIELD_BUCKETENTRY_VALUE.number()) {
                in.readLong();
            } else if (fieldNum == FIELD_BUCKETENTRY_KEYBYTES.number()) {
                final int bytesSize = in.readVarInt(false);
                in.skip(bytesSize);
            } else {
                throw new IllegalArgumentException("Unknown bucket entry field: " + fieldNum);
            }
        }
        throw new IllegalArgumentException("No bucket entry hash code found");
    }

    public void writeTo(final WritableSequentialData out) {
        bucketData.resetPosition();
        out.writeBytes(bucketData);
    }

    /**
     * First, this method updates bucket index of the current bucket to the given value. Second,
     * it iterates over all bucket entries and runs a check against entry hash codes. If the lower
     * specified number of bits of entry hash code are equal to the bucket index, the entry is
     * retained in the bucket, otherwise it's removed.
     *
     * <p>This method is used by {@link HalfDiskHashMap} after resize. During resize, no bucket
     * data is copied anywhere, but only bucket index entries are updated. It leads to some buckets
     * to have wrong numbers (some lower bits match, but higher bits are different). Besides that,
     * some bucket entries may not be valid. For example, an entry may be valid for a bucket with
     * mask 0b0111, but when the mask becomes 0b1111 as a result of map resize, the entry may now
     * belong to a bucket with a different number. This method removes all such entries.
     *
     * @param expectedIndex Bucket index to set to this bucket
     * @param expectedMaskBits Bucket mask bits to validate all bucket entries against
     */
    public void sanitize(final int expectedIndex, final int expectedMaskBits) {
        final int expectedMask = (1 << expectedMaskBits) - 1;
        bucketData.resetPosition();
        long srcIndex = 0;
        long dstIndex = 0;
        while (bucketData.hasRemaining()) {
            final long fieldOffset = bucketData.position();
            final int tag = bucketData.readVarInt(false);
            final int fieldNum = tag >> TAG_FIELD_OFFSET;
            if (fieldNum == FIELD_BUCKET_INDEX.number()) {
                bucketData.writeInt(expectedIndex);
                final long fieldLenWithTag = bucketData.position() - fieldOffset;
                srcIndex += fieldLenWithTag;
                dstIndex += fieldLenWithTag;
            } else if (fieldNum == FIELD_BUCKET_ENTRIES.number()) {
                final int entrySize = bucketData.readVarInt(false);
                final long nextEntryOffset = bucketData.position() + entrySize;
                final long entryLenWithTag = nextEntryOffset - fieldOffset;
                final long oldLimit = bucketData.limit();
                bucketData.limit(nextEntryOffset);
                final int entryHashCode = readBucketEntryHashCode(bucketData);
                bucketData.limit(oldLimit);
                if ((entryHashCode & expectedMask) == expectedIndex) {
                    copyBucketDataBytes(srcIndex, dstIndex, entryLenWithTag);
                    dstIndex += entryLenWithTag;
                }
                srcIndex += entryLenWithTag;
                bucketData.position(nextEntryOffset);
            }
        }
        bucketData.position(0);
        bucketData.limit(dstIndex);
    }

    /**
     * Copies len {@link #bucketData} bytes from src offset to dst offset.
     *
     * <p>If src and dst offsets are equal, this method is a no-op. This method makes no
     * checks against the length and the offsets, assuming they are within bucket data
     * buffer limits.
     */
    private void copyBucketDataBytes(final long src, final long dst, final long len) {
        if (src == dst) {
            return;
        }
        final long limit = bucketData.limit();
        final long pos = bucketData.position();
        final BufferedData srcBuf = bucketData.slice(src, len);
        try {
            bucketData.position(dst);
            bucketData.limit(dst + len);
            bucketData.writeBytes(srcBuf);
        } finally {
            bucketData.limit(limit);
            bucketData.position(pos);
        }
    }

    // =================================================================================================================
    // Private API

    private FindResult findEntry(final int keyHashCode, final Bytes key) {
        bucketData.resetPosition();
        while (bucketData.hasRemaining()) {
            final long fieldOffset = bucketData.position();
            final int tag = bucketData.readVarInt(false);
            final int fieldNum = tag >> TAG_FIELD_OFFSET;
            if (fieldNum == FIELD_BUCKET_INDEX.number()) {
                bucketData.skip(Integer.BYTES);
            } else if (fieldNum == FIELD_BUCKET_ENTRIES.number()) {
                final int entrySize = bucketData.readVarInt(false);
                final long nextEntryOffset = bucketData.position() + entrySize;
                final long oldLimit = bucketData.limit();
                bucketData.limit(nextEntryOffset);
                try {
                    int entryHashCode = -1;
                    long entryValueOffset = -1;
                    long entryValue = 0;
                    long entryKeyBytesOffset = -1;
                    int entryKeyBytesSize = -1;
                    while (bucketData.hasRemaining()) {
                        final int entryTag = bucketData.readVarInt(false);
                        final int entryFieldNum = entryTag >> TAG_FIELD_OFFSET;
                        if (entryFieldNum == FIELD_BUCKETENTRY_HASHCODE.number()) {
                            entryHashCode = bucketData.readInt();
                            if (entryHashCode != keyHashCode) {
                                break;
                            }
                        } else if (entryFieldNum == FIELD_BUCKETENTRY_VALUE.number()) {
                            entryValueOffset = bucketData.position();
                            entryValue = bucketData.readLong();
                        } else if (entryFieldNum == FIELD_BUCKETENTRY_KEYBYTES.number()) {
                            entryKeyBytesSize = bucketData.readVarInt(false);
                            entryKeyBytesOffset = bucketData.position();
                            bucketData.skip(entryKeyBytesSize);
                        } else {
                            throw new IllegalArgumentException("Unknown bucket entry field: " + entryFieldNum);
                        }
                    }
                    if (entryHashCode == keyHashCode) {
                        if ((entryValueOffset == -1) || (entryKeyBytesOffset == -1)) {
                            logger.warn(MERKLE_DB.getMarker(), "Broken bucket entry");
                        } else {
                            if (keyEquals(entryKeyBytesOffset, entryKeyBytesSize, key)) {
                                return new FindResult(
                                        true,
                                        fieldOffset,
                                        Math.toIntExact(nextEntryOffset - fieldOffset),
                                        entryValueOffset,
                                        entryValue);
                            }
                        }
                    }
                } finally {
                    bucketData.limit(oldLimit);
                    bucketData.position(nextEntryOffset);
                }
            } else {
                throw new IllegalArgumentException("Unknown bucket field: " + fieldNum);
            }
        }
        return FindResult.NOT_FOUND;
    }

    private boolean keyEquals(final long pos, final int size, final Bytes key) {
        if (size != key.length()) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if (bucketData.getByte(pos + i) != key.getByte(i)) {
                return false;
            }
        }
        return true;
    }

    /** toString for debugging */
    @Override
    public String toString() {
        final int entryCount = getBucketEntryCount();
        final int size = sizeInBytes();
        return "Bucket{bucketIndex=" + getBucketIndex() + ", entryCount=" + entryCount + ", size=" + size + "}";
    }

    /**
     * Simple record for entry lookup results. If an entry is found, "found" is set to true,
     * "entryOffset" is the entry offset in bytes in the bucket buffer, entrySize is the size of entry in
     * bytes, and "entryValue" is the entry value. If no entity is found, "found" is false, "entryOffset"
     * and "entrySize" are -1, and "entryValue" is undefined.
     */
    private record FindResult(boolean found, long entryOffset, int entrySize, long entryValueOffset, long entryValue) {

        static FindResult NOT_FOUND = new FindResult(false, -1, -1, -1, -1);
    }
}
