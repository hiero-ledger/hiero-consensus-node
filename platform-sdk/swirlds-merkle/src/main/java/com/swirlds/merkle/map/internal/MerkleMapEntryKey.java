// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.map.internal;

import com.swirlds.common.FastCopyable;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.common.merkle.utility.Keyed;
import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * This object is a wrapper around a {@link MerkleMapEntry}'s key.
 *
 * @param <K>
 * 		must be effectively immutable, should not implement {@link com.swirlds.common.merkle.MerkleNode}
 */
public class MerkleMapEntryKey<K extends SelfSerializable & FastCopyable> extends PartialMerkleLeaf
        implements Keyed<K>, MerkleLeaf {

    private static final long CLASS_ID = 0x16e1d5bf26e6fcf8L;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private K key;

    /**
     * Default constructor.
     */
    public MerkleMapEntryKey() {}

    /**
     * Construct this object with an initial key.
     *
     * @param key
     * 		the initial key
     */
    public MerkleMapEntryKey(final K key) {
        this.key = key;
    }

    /**
     * Copy constructor.
     *
     * @param that
     * 		the node to copy
     */
    private MerkleMapEntryKey(final MerkleMapEntryKey<K> that) {
        if (that.getKey() != null) {
            key = that.getKey().copy();
        }
        that.setImmutable(true);
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
        out.writeSerializable(key, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        key = in.readSerializable();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleMapEntryKey<K> copy() {
        return new MerkleMapEntryKey<>(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void destroyNode() {
        if (key != null) {
            key.release();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MerkleMapEntryKey)) {
            return false;
        }
        final MerkleMapEntryKey<?> that = (MerkleMapEntryKey<?>) o;
        return Objects.equals(key, that.key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(key);
    }
}
