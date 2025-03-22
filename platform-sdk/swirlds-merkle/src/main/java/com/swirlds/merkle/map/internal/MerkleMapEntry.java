// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.map.internal;

import com.swirlds.common.FastCopyable;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;
import com.swirlds.common.merkle.utility.Keyed;
import com.swirlds.merkle.map.MerkleMap;
import java.util.Objects;
import org.hiero.consensus.model.io.SelfSerializable;

/**
 * A wrapper class designed to be used as a value in a {@link MerkleMap MerkleMap}. Useful when it is inconvenient to
 * store a non-merkle key directly in the value.
 *
 * @param <K>
 * 		the type of the key in the {@link MerkleMap MerkleMap}. This type SHOULD NOT be a merkle type,
 * 		else there is significant memory overhead
 * @param <V>
 * 		an arbitrary merkle type that is being stored in the MerkleMap
 */
public class MerkleMapEntry<K extends FastCopyable & SelfSerializable, V extends MerkleNode>
        extends PartialBinaryMerkleInternal implements Keyed<K>, MerkleInternal {

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    public static final long CLASS_ID = 0x19bddb9a9d69bfd0L;

    /**
     * Create a new entry.
     *
     * @param key
     * 		the key
     * @param value
     * 		the value
     */
    public MerkleMapEntry(final K key, final V value) {
        super();
        setLeft(new MerkleMapEntryKey<K>(key));
        setRight(value);
    }

    /**
     * Create a new entry without specifying the key.
     * If inserted into a {@link MerkleMap} then the key will automatically be set.
     *
     * @param value
     * 		the value of the entry
     */
    public MerkleMapEntry(final V value) {
        super();
        setRight(value);
    }

    /**
     * Create a new merkle pair.
     */
    public MerkleMapEntry() {
        super();
    }

    /**
     * Copy constructor.
     *
     * @param that
     * 		the node to copy
     */
    protected MerkleMapEntry(final MerkleMapEntry<K, V> that) {
        super(that);
        that.setImmutable(true);

        if (that.getLeft() != null) {
            setLeft(that.getLeft().copy().cast());
        }
        if (that.getRight() != null) {
            setRight(that.getRight().copy().cast());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof MerkleMapEntry)) {
            return false;
        }

        final MerkleMapEntry<?, ?> otherLeaf = (MerkleMapEntry<?, ?>) object;
        return Objects.equals(getLeft(), otherLeaf.getLeft()) && Objects.equals(getRight(), otherLeaf.getRight());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(getLeft(), getRight());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleMapEntry<K, V> copy() {
        throwIfImmutable();
        throwIfDestroyed();
        return new MerkleMapEntry<>(this);
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
        return MerkleMapEntry.ClassVersion.ORIGINAL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public K getKey() {
        final MerkleMapEntryKey<K> key = getLeft();
        return key == null ? null : key.getKey();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setKey(final K key) {
        if (getLeft() == null) {
            setLeft(new MerkleMapEntryKey<>(key));
        } else {
            final MerkleMapEntryKey<K> keyWrapper = getLeft();
            keyWrapper.setKey(key);
        }
    }

    /**
     * Get the value of this entry.
     */
    public V getValue() {
        return getRight();
    }

    /**
     * Set the value of this entry.
     *
     * @param value
     * 		the new value
     */
    public void setValue(final V value) {
        setRight(value);
    }
}
