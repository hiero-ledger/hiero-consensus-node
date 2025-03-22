// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * An object for leaf data. The leaf record contains the path, key, and value.
 * This record is {@link SelfSerializable} to support reconnect and state saving, where it is necessary
 * to take leaf records from caches that are not yet flushed to disk and write them to the stream.
 * We never send hashes in the stream.
 */
public final class VirtualLeafRecord<K extends VirtualKey, V extends VirtualValue> implements SelfSerializable {

    private static final long CLASS_ID = 0x410f45f0acd3264L;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    /**
     * The path for this record. The path can change over time as nodes are added or removed.
     */
    private volatile long path;

    // key and value are effectively immutable, as they are being set only in the constructor and deserialize method,
    // therefore they don't need to be volatile. They can't be final because of the deserialize method.
    private K key;
    private V value;

    /**
     * Create a new leaf record. This constructor is <strong>only</strong> used by the serialization engine.
     * It creates a leaf with a totally invalid leaf path.
     */
    public VirtualLeafRecord() {
        path = -1;
    }

    /**
     * Create a new leaf record, supplying all data required by the record.
     *
     * @param path
     * 		The path. Must be positive (since 0 represents a root node, which is never a leaf),
     * 		or {@link Path#INVALID_PATH}.
     * @param key
     * 		The key for this record. This should normally never be null, but may be for
     *        {@link VirtualNodeCache#DELETED_LEAF_RECORD}
     * 		or other uses where the leaf record is meant to represent some invalid state.
     * @param value
     * 		The value for this record, which can be null.
     */
    public VirtualLeafRecord(final long path, final K key, final V value) {
        this.path = path;
        this.key = key;
        this.value = value;
    }

    @SuppressWarnings("unchecked")
    public VirtualLeafRecord<K, V> copy() {
        return new VirtualLeafRecord<>(path, key, (V) value.copy());
    }

    /**
     * Gets the key.
     * @return
     *        The key. This <strong>may</strong> be null in some cases, such as when the record is meant to
     *		represent an invalid state, or when it is in the middle of serialization. No leaf that represents
     *		an actual leaf will ever return null here.
     */
    public K getKey() {
        return key;
    }

    /**
     * Gets the value.
     * @return
     *        The value. May be null.
     */
    public V getValue() {
        return value;
    }

    public void setPath(long path) {
        this.path = path;
    }

    public long getPath() {
        return path;
    }

    /**
     * Sets the value. May be null. Must set the hash to null if the value has changed.
     *
     * @param value
     * 		The value.
     */
    public void setValue(final V value) {
        if (this.value != value) {
            this.value = value;
        }
    }

    public VirtualLeafBytes toBytes(final KeySerializer<K> keySerializer, final ValueSerializer<V> valueSerializer) {
        if (key == null) {
            throw new IllegalStateException("Leaf records with null keys should not be serialized");
        }
        final byte[] keyBytes = new byte[keySerializer.getSerializedSize(key)];
        keySerializer.serialize(key, BufferedData.wrap(keyBytes));
        final byte[] valueBytes;
        if (value != null) {
            valueBytes = new byte[valueSerializer.getSerializedSize(value)];
            valueSerializer.serialize(value, BufferedData.wrap(valueBytes));
        } else {
            valueBytes = null;
        }
        return new VirtualLeafBytes(
                path, Bytes.wrap(keyBytes), key.hashCode(), valueBytes != null ? Bytes.wrap(valueBytes) : null);
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
        out.writeLong(path);
        out.writeSerializable(key, true);
        out.writeSerializable(value, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        this.path = in.readLong();
        this.key = in.readSerializable();
        this.value = in.readSerializable();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final VirtualLeafRecord<?, ?> that = (VirtualLeafRecord<?, ?>) o;
        return path == that.path && Objects.equals(key, that.key) && Objects.equals(value, that.value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(path, key, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("key", key)
                .append("value", value)
                .append("path", path)
                .toString();
    }
}
