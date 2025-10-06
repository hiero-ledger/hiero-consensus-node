// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.io.IOException;
import java.util.Objects;
import org.hiero.base.constructable.ConstructableIgnored;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * Implementation of a VirtualLeaf
 */
@ConstructableIgnored
@SuppressWarnings("rawtypes")
public final class VirtualLeafNode extends PartialMerkleLeaf implements MerkleLeaf, VirtualNode {

    public static final long CLASS_ID = 0x499677a326fb04caL;

    private static class ClassVersion {

        public static final int ORIGINAL = 1;
    }

    /**
     * The {@link VirtualLeafBytes} is the backing data for this node.
     */
    private final VirtualLeafBytes virtualRecord;

    public VirtualLeafNode(final VirtualLeafBytes virtualLeafBytes, final Hash hash) {
        this.virtualRecord = Objects.requireNonNull(virtualLeafBytes);
        setHash(hash);
    }

    @Override
    public long getPath() {
        return virtualRecord.path();
    }

    /**
     * Get the key represented held within this leaf.
     *
     * @return the key
     */
    public Bytes getKey() {
        return virtualRecord.keyBytes();
    }

    /**
     * Get the value held within this leaf.
     *
     * @return the value
     */
    public Bytes getValue() {
        return virtualRecord.valueBytes();
    }

    @SuppressWarnings("unchecked")
    public <V> V getValue(final Codec<V> valueCodec) {
        return ((VirtualLeafBytes<V>) virtualRecord).value(valueCodec);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualLeafNode copy() {
        throw new UnsupportedOperationException("Don't use this");
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
    public String toString() {
        return new ToStringBuilder(this).append(virtualRecord).toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        throw new UnsupportedOperationException();
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
        final VirtualLeafNode that = (VirtualLeafNode) o;
        return virtualRecord.equals(that.virtualRecord);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return virtualRecord.hashCode();
    }
}
