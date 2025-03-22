// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.utility;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.FastCopyable;
import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A utility node that contains a long value and implements {@link Keyed}.
 */
public class KeyedMerkleLong<K extends FastCopyable & SelfSerializable> extends MerkleLong implements Keyed<K> {

    public static final long CLASS_ID = 0xe16e0c4f1b0e7c92L;

    private static final int CLASS_VERSION = 1;

    private long value;
    private K key;

    /**
     * Create a new KeyedMerkleLong with a key of 0 and a value of null.
     */
    public KeyedMerkleLong() {}

    /**
     * Create a new KeyedMerkleLong with a given value and a key of 0.
     *
     * @param value
     * 		the value
     */
    public KeyedMerkleLong(final long value) {
        this.value = value;
    }

    /**
     * Create a new KeyedMerkleLong with a given key and value.
     *
     * @param key
     * 		the key
     * @param value
     * 		the value
     */
    public KeyedMerkleLong(final K key, final long value) {
        this.value = value;
        this.key = key;
    }

    @SuppressWarnings("unchecked")
    private KeyedMerkleLong(final KeyedMerkleLong<K> that) {
        super(that);
        this.value = that.value;
        if (that.key != null) {
            this.key = (K) that.key.copy();
        }
        that.setImmutable(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public KeyedMerkleLong<K> copy() {
        throwIfImmutable();
        return new KeyedMerkleLong<>(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeLong(value);
        out.writeSerializable(key, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        value = in.readLong();
        key = in.readSerializable();
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
        return CLASS_VERSION;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this).append("value", value).toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public K getKey() {
        return key;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setKey(final K key) {
        this.key = key;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MerkleLong)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        final KeyedMerkleLong<?> that = (KeyedMerkleLong<?>) o;
        return value == that.value && Objects.equals(key, that.key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), value, key);
    }
}
