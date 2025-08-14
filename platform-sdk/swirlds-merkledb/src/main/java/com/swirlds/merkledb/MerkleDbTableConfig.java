// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import java.io.IOException;
import java.util.Objects;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * Virtual database table configuration. It describes how to store virtual keys and values
 * and a few other params like whether to prefer disk based indexes or not. These table
 * configs are stored in virtual database metadata and persisted across JVM runs.
 */
@Deprecated
public final class MerkleDbTableConfig implements SelfSerializable {

    private static final long CLASS_ID = 0xbb41e7eb9fcad23cL;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private static final FieldDefinition FIELD_TABLECONFIG_HASHVERSION =
            new FieldDefinition("hashVersion", FieldType.UINT32, false, true, false, 1);

    private static final FieldDefinition FIELD_TABLECONFIG_DIGESTTYPEID =
            new FieldDefinition("digestTypeId", FieldType.UINT32, false, false, false, 2);

    @Deprecated
    private static final FieldDefinition FIELD_TABLECONFIG_KEYVERSION =
            new FieldDefinition("keyVersion", FieldType.UINT32, false, true, false, 3);

    @Deprecated
    private static final FieldDefinition FIELD_TABLECONFIG_KEYSERIALIZERCLSID =
            new FieldDefinition("keySerializerClassId", FieldType.UINT64, false, false, false, 4);

    @Deprecated
    private static final FieldDefinition FIELD_TABLECONFIG_VALUEVERSION =
            new FieldDefinition("valueVersion", FieldType.UINT32, false, true, false, 5);

    @Deprecated
    private static final FieldDefinition FIELD_TABLECONFIG_VALUESERIALIZERCLSID =
            new FieldDefinition("valueSerializerClassId", FieldType.UINT64, false, false, false, 6);

    @Deprecated
    private static final FieldDefinition FIELD_TABLECONFIG_PREFERDISKINDICES =
            new FieldDefinition("preferDiskIndices", FieldType.UINT32, false, true, false, 7);

    private static final FieldDefinition FIELD_TABLECONFIG_INITIALCAPACITY =
            new FieldDefinition("initialCapacity", FieldType.UINT64, false, true, false, 8);

    private static final FieldDefinition FIELD_TABLECONFIG_HASHRAMTODISKTHRESHOLD =
            new FieldDefinition("hashesRamToDiskThreshold", FieldType.UINT64, false, true, false, 9);

    /**
     * Hash version.
     */
    private short hashVersion;

    /**
     * Hash type. Only SHA_384 is supported yet.
     */
    private DigestType hashType;

    /**
     * Max number of keys that can be stored in a table.
     */
    private long initialCapacity;

    /**
     * Threshold where we switch from storing internal hashes in ram to storing them on disk. If it is 0 then everything
     * is on disk, if it is Long.MAX_VALUE then everything is in ram. Any value in the middle is the path value at which
     * we swap from ram to disk. This allows a tree where the lower levels of the tree nodes hashes are in ram and the
     * upper larger less changing layers are on disk.
     */
    private long hashesRamToDiskThreshold;

    /**
     * Creates a new virtual table config with default values. This constructor should only be used
     * for deserialization.
     */
    public MerkleDbTableConfig() {
        // required for deserialization
    }

    /**
     * Creates a new virtual table config with the specified params.
     *
     * @param hashVersion
     *      Hash version
     * @param hashType
     *      Hash type
     * @param initialCapacity
     *      Initial database capacity. May be used to calculate index sizes
     * @param hashesRamToDiskThreshold
     *      Threshold where we switch from storing internal hashes in ram to storing them on disk.
     */
    public MerkleDbTableConfig(
            final short hashVersion,
            final DigestType hashType,
            final long initialCapacity,
            final long hashesRamToDiskThreshold) {
        // Mandatory fields
        this.hashVersion = hashVersion;
        this.hashType = hashType;

        // Optional hints, may be set explicitly using setters later. Defaults are loaded from
        // MerkleDb configuration
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Initial capacity must be greater than 0");
        }
        this.initialCapacity = initialCapacity;
        if (hashesRamToDiskThreshold < 0) {
            throw new IllegalArgumentException("Hashes RAM/disk threshold must be greater or equal to 0");
        }
        this.hashesRamToDiskThreshold = hashesRamToDiskThreshold;
    }

    public MerkleDbTableConfig(final ReadableSequentialData in) {
        // Defaults. If a field is missing in the input, a default protobuf value is used
        // (zero, false, null, etc.) rather than a default value from MerkleDb config. The
        // config is used for defaults when a new table config is created, but when an
        // existing config is loaded, only values from the input must be used (even if some
        // of them are protobuf default and aren't present)
        hashVersion = 0;
        hashType = DigestType.SHA_384;
        initialCapacity = 0;
        hashesRamToDiskThreshold = 0;

        while (in.hasRemaining()) {
            final int tag = in.readVarInt(false);
            final int fieldNum = tag >> TAG_FIELD_OFFSET;
            if (fieldNum == FIELD_TABLECONFIG_HASHVERSION.number()) {
                hashVersion = (short) in.readVarInt(false);
            } else if (fieldNum == FIELD_TABLECONFIG_DIGESTTYPEID.number()) {
                final int digestTypeId = in.readVarInt(false);
                hashType = DigestType.valueOf(digestTypeId);
            } else if (fieldNum == FIELD_TABLECONFIG_KEYVERSION.number()) {
                // Skip key version
                in.readVarInt(false);
            } else if (fieldNum == FIELD_TABLECONFIG_KEYSERIALIZERCLSID.number()) {
                final long classId = in.readVarLong(false);
                throw new UnsupportedOperationException("Cannot deserialize MerkleDb table config with key serializer");
            } else if (fieldNum == FIELD_TABLECONFIG_VALUEVERSION.number()) {
                // Skip value version
                in.readVarInt(false);
            } else if (fieldNum == FIELD_TABLECONFIG_VALUESERIALIZERCLSID.number()) {
                final long classId = in.readVarLong(false);
                throw new UnsupportedOperationException(
                        "Cannot deserialize MerkleDb table config with value serializer");
            } else if (fieldNum == FIELD_TABLECONFIG_PREFERDISKINDICES.number()) {
                // Skip preferDiskIndices
                in.readVarInt(false);
            } else if (fieldNum == FIELD_TABLECONFIG_INITIALCAPACITY.number()) {
                initialCapacity = in.readVarLong(false);
            } else if (fieldNum == FIELD_TABLECONFIG_HASHRAMTODISKTHRESHOLD.number()) {
                hashesRamToDiskThreshold = in.readVarLong(false);
            } else {
                throw new IllegalArgumentException("Unknown table config field: " + fieldNum);
            }
        }

        // Check that all mandatory fields have been loaded from the stream
        requireNonNull(hashType, "Null or wrong hash type");
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Missing or wrong initial capacity");
        }
    }

    public int pbjSizeInBytes() {
        int size = 0;
        if (hashVersion != 0) {
            size += ProtoWriterTools.sizeOfTag(
                    FIELD_TABLECONFIG_HASHVERSION, ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG);
            size += ProtoWriterTools.sizeOfVarInt32(hashVersion);
        }
        size += ProtoWriterTools.sizeOfTag(FIELD_TABLECONFIG_DIGESTTYPEID, ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG);
        size += ProtoWriterTools.sizeOfVarInt32(hashType.id());
        assert initialCapacity != 0;
        size += ProtoWriterTools.sizeOfTag(
                FIELD_TABLECONFIG_INITIALCAPACITY, ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG);
        size += ProtoWriterTools.sizeOfVarInt64(initialCapacity);
        if (hashesRamToDiskThreshold != 0) {
            size += ProtoWriterTools.sizeOfTag(
                    FIELD_TABLECONFIG_HASHRAMTODISKTHRESHOLD, ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG);
            size += ProtoWriterTools.sizeOfVarInt64(hashesRamToDiskThreshold);
        }
        return size;
    }

    public void writeTo(final WritableSequentialData out) {
        if (hashVersion != 0) {
            ProtoWriterTools.writeTag(out, FIELD_TABLECONFIG_HASHVERSION);
            out.writeVarInt(hashVersion, false);
        }
        ProtoWriterTools.writeTag(out, FIELD_TABLECONFIG_DIGESTTYPEID);
        out.writeVarInt(hashType.id(), false);
        assert initialCapacity != 0;
        ProtoWriterTools.writeTag(out, FIELD_TABLECONFIG_INITIALCAPACITY);
        out.writeVarLong(initialCapacity, false);
        if (hashesRamToDiskThreshold != 0) {
            ProtoWriterTools.writeTag(out, FIELD_TABLECONFIG_HASHRAMTODISKTHRESHOLD);
            out.writeVarLong(hashesRamToDiskThreshold, false);
        }
    }

    /**
     * Hash version.
     *
     * @return
     *      Hash version
     */
    public short getHashVersion() {
        return hashVersion;
    }

    /**
     * Hash type.
     *
     * @return
     *      Hash type
     */
    public DigestType getHashType() {
        return hashType;
    }

    /**
     * Initial database capacity.
     *
     * @return
     *      Initial database capacity
     */
    public long getInitialCapacity() {
        return initialCapacity;
    }

    /**
     * Internal hashes RAM/disk threshold. Value {@code 0} means all hashes are to be stored on disk.
     * Value {@link Integer#MAX_VALUE} indicates that all hashes are to be stored in memory.
     *
     * @return
     *      Internal hashes RAM/disk threshold
     */
    public long getHashesRamToDiskThreshold() {
        return hashesRamToDiskThreshold;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeBoolean(false); // prefer disk indices
        out.writeLong(initialCapacity);
        out.writeLong(hashesRamToDiskThreshold);
        out.writeShort(hashVersion);
        out.writeInt(hashType.id());
        out.writeShort(0); // key version
        out.writeSerializable(null, true); // key serializer
        out.writeShort(0); // value version
        out.writeSerializable(null, true); // value serializer
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        in.readBoolean(); // prefer disk indices
        initialCapacity = in.readLong();
        hashesRamToDiskThreshold = in.readLong();
        hashVersion = in.readShort();
        hashType = DigestType.valueOf(in.readInt());
        in.readShort(); // key version
        in.readSerializable(); // key serializer
        in.readShort(); // value version
        in.readSerializable(); // value serializer
    }

    /**
     * Creates a copy of this table config.
     *
     * @return Table config copy
     */
    public MerkleDbTableConfig copy() {
        return new MerkleDbTableConfig(hashVersion, hashType, initialCapacity, hashesRamToDiskThreshold);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(hashVersion, hashType, initialCapacity, hashesRamToDiskThreshold);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object o) {
        if (!(o instanceof MerkleDbTableConfig other)) {
            return false;
        }
        return (initialCapacity == other.initialCapacity)
                && (hashesRamToDiskThreshold == other.hashesRamToDiskThreshold)
                && (hashVersion == other.hashVersion)
                && Objects.equals(hashType, other.hashType);
    }
}
