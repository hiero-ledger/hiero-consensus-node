// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.utils;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class LongLeaf extends PartialMerkleLeaf implements MerkleLeaf {
    // For serialization
    private static final long CLASS_ID = 998829382944382L;
    private static final int VERSION_0 = 0;
    private static final int CURRENT_VERSION = VERSION_0;

    /** The value of the leaf */
    private long value = 0L;

    public LongLeaf() {}

    /**
     * Create a new instance with the given value for the leaf.
     *
     * @param value The value cannot be null.
     */
    public LongLeaf(long value) {
        this.value = value;
    }

    /**
     * Gets the value.
     *
     * @return the value.
     */
    public long getValue() {
        return value;
    }

    /** {@inheritDoc} */
    @Override
    public LongLeaf copy() {
        throwIfImmutable();
        throwIfDestroyed();
        setImmutable(true);
        return new LongLeaf(value);
    }

    /** {@inheritDoc} */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /** {@inheritDoc} */
    @Override
    public void deserialize(final SerializableDataInputStream in, int ver) throws IOException {
        // We cannot parse streams from future versions.
        if (ver > VERSION_0) {
            throw new IllegalArgumentException("The version number of the stream is too new.");
        }

        this.value = in.readLong();
    }

    /** {@inheritDoc} */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeLong(this.value);
    }

    /** {@inheritDoc} */
    @Override
    public int getVersion() {
        return CURRENT_VERSION;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LongLeaf longLeaf)) return false;
        return value == longLeaf.value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
