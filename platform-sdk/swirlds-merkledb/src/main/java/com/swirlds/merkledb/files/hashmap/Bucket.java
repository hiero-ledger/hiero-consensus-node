// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files.hashmap;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 *     // Entry path
 *     int64 path = 2;
 *
 *     // Serialized key
 *     bytes key = 3;
 *
 *     // Serialized path
 *     bytes valueBytes = 4;
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
    protected static final FieldDefinition FIELD_BUCKETENTRY_PATH =
            new FieldDefinition("path", FieldType.FIXED64, false, false, false, 2);
    protected static final FieldDefinition FIELD_BUCKETENTRY_KEY =
            new FieldDefinition("key", FieldType.BYTES, false, false, false, 3);
    protected static final FieldDefinition FIELD_BUCKETENTRY_VALUE =
            new FieldDefinition("value", FieldType.BYTES, false, false, false, 4);

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

    /**
     * Get the number of entries stored in this bucket. For testing purposes only.
     */
    int getBucketEntryCount() {
        bucketData.resetPosition();
        int entryCount = 0;
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
                logger.error(MERKLE_DB.getMarker(), "Unknown bucket field: {}", tag);
                throw new IllegalArgumentException("Unknown bucket field: " + fieldNum);
            }
        }
        return entryCount;
    }

    protected void checkLargestBucket() {
        if (!logger.isDebugEnabled(MERKLE_DB.getMarker())) {
            return;
        }
        final int count = getBucketEntryCount();
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
     * Find a leaf for a given key.
     *
     * @param key the key object
     * @param keyHashCode the int hash for the key
     * @return the stored leaf for the given key or {@code null} if nothing is stored for the key
     */
    public VirtualLeafBytes<?> findLeaf(final Bytes key, final int keyHashCode) {
        final FindResult result = findEntry(keyHashCode, key);
        if (result.found()) {
            // yay! we found it
            bucketData.position(result.valueOffset);
            final Bytes valueBytes = bucketData.readBytes(result.valueSize());
            return new VirtualLeafBytes<>(result.path(), key, valueBytes);
        } else {
            return null;
        }
    }

    public long findPath(final Bytes key, final int keyHashCode) {
        final FindResult result = findEntry(keyHashCode, key);
        if (result.found()) {
            // yay! we found it
            return result.path();
        } else {
            return Path.INVALID_PATH;
        }
    }

    /**
     * Put a leaf into this bucket.
     *
     * @param key the entry key
     * @param path the entry path, this can also be special
     *     HalfDiskHashMap.INVALID_VALUE to mean delete
     */
    public final void putLeaf(final Bytes key, final int keyHashCode, final long path, final Bytes value) {
        putLeaf(key, keyHashCode, Path.INVALID_PATH, path, value);
    }

    /**
     * Optionally check the current path, and if it matches the given path, then put a
     * leaf into this bucket. If the existing path check is requested, but there
     * is no existing path for the key, the leaf is not added.
     *
     * <p>This method returns a boolean that indicates there were some changes to the
     * bucket, so that the new path is different from the existing path. If the existing
     * path check is requested, but failed, this method returns {@code false}, since no
     * updates are performed.
     *
     * @param key the entry key
     * @param keyHashCode the key hash code
     * @param oldPath the path to check the existing path against.
     *          If it's {@link Path#INVALID_PATH}, no check is performed
     * @param path the entry path.
     *          If it's {@link Path#INVALID_PATH}, the leaf is deleted
     * @param value the entry value.
     *          Can be {@code null}, only if {@code path} is {@link Path#INVALID_PATH}
     * @return {@code true} if the bucket was changed or not
     */
    public boolean putLeaf(
            @NonNull final Bytes key,
            final int keyHashCode,
            final long oldPath,
            final long path,
            @Nullable final Bytes value) {
        final boolean needCheckOldPath = oldPath != Path.INVALID_PATH;
        final FindResult result = findEntry(keyHashCode, key);
        if (path == Path.INVALID_PATH) {
            assert value == null;
            if (result.found()) {
                if (needCheckOldPath && (oldPath != result.path)) {
                    return false;
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
                // entry removed -> bucket is updated
                return true;
            } else {
                // entry not found, nothing to delete -> bucket is not updated
                return false;
            }
        }
        assert value != null;
        if (result.found()) {
            // yay! we found it, so update the leaf
            if (needCheckOldPath && (oldPath != result.path)) {
                return false;
            }
            bucketData.position(result.pathOffset());
            bucketData.writeLong(path);
            if (value.length() > result.valueSize()) {
                extend(result.entryOffset + result.entrySize, Math.toIntExact(value.length() - result.valueSize()));
            } else if (value.length() < result.valueSize()) {
                shrink(result.entryOffset + result.entrySize, Math.toIntExact(result.valueSize() - value.length()));
            }
            bucketData.position(result.valueOffset());
            bucketData.writeBytes(value);
            return true;
        } else {
            if (needCheckOldPath) {
                // no existing path, but a check is requested
                return false;
            }
            // add a new entry
            writeNewEntry(keyHashCode, path, key, value);
            checkLargestBucket();
            // entry added -> bucket updated
            return true;
        }
    }

    private void extend(final int offset, final int delta) {
        final int oldSize = Math.toIntExact(bucketData.length());
        final int newSize = Math.toIntExact(oldSize + delta);
        setSize(newSize, true);
        final BufferedData remainder = bucketData.slice(offset, oldSize - offset);
        bucketData.position(offset + delta);
        bucketData.writeBytes(remainder);
    }

    private void shrink(final int offset, final int delta) {
        final int oldSize = Math.toIntExact(bucketData.length());
        final int newSize = Math.toIntExact(oldSize - delta);
        final BufferedData remainder = bucketData.slice(offset, oldSize - offset);
        bucketData.position(offset - delta);
        bucketData.writeBytes(remainder);
        setSize(newSize, true);
    }

    /**
     * This method is similar to {@link #putLeaf(Bytes, int, long, Bytes)}, but doesn't check if the
     * key is in the bucket, just adds a new bucket entry. It can be useful to put values to
     * empty (new) buckets and skip some checks.
     */
    public void addValue(
            @NonNull final Bytes key,
            final int keyHashCode,
            final long path,
            @NonNull final Bytes value) {
        writeNewEntry(keyHashCode, path, key, value);
        checkLargestBucket();
    }

    private void writeNewEntry(final int hashCode, final long path, final Bytes key, final Bytes value) {
        final long entryOffset = bucketData.limit();
        final int keySize = Math.toIntExact(key.length());
        final int valueSize = Math.toIntExact(value.length());
        final int entrySize =
                ProtoWriterTools.sizeOfTag(FIELD_BUCKETENTRY_HASHCODE, ProtoConstants.WIRE_TYPE_FIXED_32_BIT)
                        + Integer.BYTES
                        + ProtoWriterTools.sizeOfTag(FIELD_BUCKETENTRY_PATH, ProtoConstants.WIRE_TYPE_FIXED_64_BIT)
                        + Long.BYTES
                        + ProtoWriterTools.sizeOfDelimited(FIELD_BUCKETENTRY_KEY, keySize)
                        + ProtoWriterTools.sizeOfDelimited(FIELD_BUCKETENTRY_VALUE, valueSize);
        final int totalSize = ProtoWriterTools.sizeOfDelimited(FIELD_BUCKET_ENTRIES, entrySize);
        setSize(Math.toIntExact(entryOffset + totalSize), true);
        bucketData.position(entryOffset);
        ProtoWriterTools.writeDelimited(bucketData, FIELD_BUCKET_ENTRIES, entrySize, out -> {
            ProtoWriterTools.writeTag(out, FIELD_BUCKETENTRY_HASHCODE);
            out.writeInt(hashCode);
            ProtoWriterTools.writeTag(out, FIELD_BUCKETENTRY_PATH);
            out.writeLong(path);
            ProtoWriterTools.writeDelimited(out, FIELD_BUCKETENTRY_KEY, keySize, t -> t.writeBytes(key));
            ProtoWriterTools.writeDelimited(out, FIELD_BUCKETENTRY_VALUE, valueSize, t -> t.writeBytes(value));
        });
    }

    public void readFrom(final ReadableSequentialData in) {
        final int size = Math.toIntExact(in.remaining());
        setSize(size, false);
        final int bytesRead = Math.toIntExact(in.readBytes(bucketData));
        assert bytesRead == size;
        bucketData.flip();

        bucketIndexFieldOffset = 0;
        while (bucketData.hasRemaining()) {
            final long fieldOffset = bucketData.position();
            final int tag = bucketData.readVarInt(false);
            final int fieldNum = tag >> TAG_FIELD_OFFSET;
            if (fieldNum == FIELD_BUCKET_INDEX.number()) {
                bucketIndexFieldOffset = fieldOffset;
                break;
            } else if (fieldNum == FIELD_BUCKET_ENTRIES.number()) {
                final int entryBytesSize = bucketData.readVarInt(false);
                bucketData.skip(entryBytesSize);
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

        checkLargestBucket();
    }

    private static int readBucketEntryHashCode(final ReadableSequentialData in) {
        while (in.hasRemaining()) {
            final int tag = in.readVarInt(false);
            final int fieldNum = tag >> TAG_FIELD_OFFSET;
            if (fieldNum == FIELD_BUCKETENTRY_HASHCODE.number()) {
                return in.readInt();
            } else if (fieldNum == FIELD_BUCKETENTRY_PATH.number()) {
                in.readLong();
            } else if (fieldNum == FIELD_BUCKETENTRY_KEY.number()) {
                final int keyLen = in.readVarInt(false);
                in.skip(keyLen);
            } else if (fieldNum == FIELD_BUCKETENTRY_VALUE.number()) {
                final int valueLen = in.readVarInt(false);
                in.skip(valueLen);
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
     * First, this method updates bucket index of the current bucket to the given path. Second,
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
     * @return if the bucket was changed by this method
     */
    public boolean sanitize(final int expectedIndex, final int expectedMaskBits) {
        final int expectedMask = (1 << expectedMaskBits) - 1;
        bucketData.resetPosition();
        long srcIndex = 0;
        long dstIndex = 0;
        boolean updated = false;
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
                } else {
                    updated = true;
                }
                srcIndex += entryLenWithTag;
                bucketData.position(nextEntryOffset);
            }
        }
        bucketData.position(0);
        bucketData.limit(dstIndex);
        return updated;
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
            final int fieldOffset = Math.toIntExact(bucketData.position());
            final int tag = bucketData.readVarInt(false);
            final int fieldNum = tag >> TAG_FIELD_OFFSET;
            if (fieldNum == FIELD_BUCKET_INDEX.number()) {
                bucketData.skip(Integer.BYTES);
            } else if (fieldNum == FIELD_BUCKET_ENTRIES.number()) {
                final int entrySize = bucketData.readVarInt(false);
                final int nextEntryOffset = Math.toIntExact(bucketData.position() + entrySize);
                final long oldLimit = bucketData.limit();
                bucketData.limit(nextEntryOffset);
                try {
                    int hashCode = -1;
                    int pathOffset = -1;
                    long path = 0;
                    int keyOffset = -1;
                    int keySize = -1;
                    int valueOffset = -1;
                    int valueSize = -1;
                    while (bucketData.hasRemaining()) {
                        final int entryTag = bucketData.readVarInt(false);
                        final int entryFieldNum = entryTag >> TAG_FIELD_OFFSET;
                        if (entryFieldNum == FIELD_BUCKETENTRY_HASHCODE.number()) {
                            hashCode = bucketData.readInt();
                            if (hashCode != keyHashCode) {
                                break;
                            }
                        } else if (entryFieldNum == FIELD_BUCKETENTRY_PATH.number()) {
                            pathOffset = Math.toIntExact(bucketData.position());
                            path = bucketData.readLong();
                        } else if (entryFieldNum == FIELD_BUCKETENTRY_KEY.number()) {
                            keySize = bucketData.readVarInt(false);
                            if (keySize != key.length()) {
                                break;
                            }
                            keyOffset = Math.toIntExact(bucketData.position());
                            bucketData.skip(keySize);
                        } else if (entryFieldNum == FIELD_BUCKETENTRY_VALUE.number()) {
                            valueSize = bucketData.readVarInt(false);
                            valueOffset = Math.toIntExact(bucketData.position());
                            bucketData.skip(valueSize);
                        } else {
                            throw new IllegalArgumentException("Unknown bucket entry field: " + entryFieldNum);
                        }
                    }
                    if ((hashCode == keyHashCode) && (keySize == key.length())) {
                        if (pathOffset == -1) {
                            logger.warn(MERKLE_DB.getMarker(), "Broken bucket entry");
                        } else {
                            if (keyEquals(keyOffset, key)) {
                                assert valueOffset > 0;
                                assert valueSize > 0;
                                return new FindResult(
                                        true,
                                        fieldOffset,
                                        nextEntryOffset - fieldOffset,
                                        pathOffset,
                                        path,
                                        valueOffset,
                                        valueSize);
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

    private boolean keyEquals(final long pos, final Bytes key) {
        final int size = (int) key.length();
        for (int i = 0; i < size; i++) {
            if (bucketData.getByte(pos + i) != key.getByte(i)) {
                return false;
            }
        }
        return true;
    }

//    /** toString for debugging */
//    @Override
//    public String toString() {
//        final int entryCount = getBucketEntryCount();
//        final int size = sizeInBytes();
//        return "Bucket{bucketIndex=" + getBucketIndex() + ", entryCount=" + entryCount + ", size=" + size + "}";
//    }

    /**
     * Simple record for entry lookup results. If an entry is found, "found" is set to true,
     * "entryOffset" is the entry offset in bytes in the bucket buffer, entrySize is the size of entry in
     * bytes, and "path" is the entry path. If no entity is found, "found" is false, "entryOffset"
     * and "entrySize" are -1, and "path" is undefined.
     */
    private record FindResult(
            boolean found,
            int entryOffset,
            int entrySize,
            int pathOffset,
            long path,
            int valueOffset,
            int valueSize) {

        static FindResult NOT_FOUND = new FindResult(false, -1, -1, -1, -1, -1, -1);
    }
}
