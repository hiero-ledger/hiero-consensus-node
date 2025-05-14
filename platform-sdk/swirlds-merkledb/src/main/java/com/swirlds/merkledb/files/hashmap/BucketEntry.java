// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files.hashmap;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;

import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A single entry in a bucket, which contains key hash code, value (usually, path), and full serialized key. A bucket
 * may contain multiple such entries.
 *
 * <p>This class would be a record, if it was immutable. However, when a value is updated
 * in a bucket, and a bucket entry already exists for the same key, instead of creating a new entry, we just update the
 * value in the existing entry.
 */
public class BucketEntry {

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
        size += ProtoWriterTools.sizeOfDelimited(Bucket.FIELD_BUCKETENTRY_KEYBYTES, Math.toIntExact(keyBytes.length()));
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
