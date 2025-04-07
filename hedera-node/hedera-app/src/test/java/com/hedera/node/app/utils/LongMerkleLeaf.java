// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.utils;

import com.swirlds.common.merkle.utility.Keyed;
import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class LongMerkleLeaf extends LongLeaf implements Keyed<Long> {
    // For serialization
    private static final long CLASS_ID = 998829382944383L;
    private static final int VERSION_0 = 0;
    private static final int CURRENT_VERSION = VERSION_0;

    private long key = 0L;

    public LongMerkleLeaf() {}

    public LongMerkleLeaf(long key, long value) {
        super(value);
        this.key = key;
    }

    @Override
    public Long getKey() {
        return key;
    }

    @Override
    public void setKey(Long aLong) {
        this.key = aLong;
    }

    /** {@inheritDoc} */
    @Override
    public LongMerkleLeaf copy() {
        throwIfImmutable();
        throwIfDestroyed();
        setImmutable(true);
        return new LongMerkleLeaf(key, getValue());
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

        super.deserialize(in, ver);
        this.key = in.readLong();
    }

    /** {@inheritDoc} */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        super.serialize(out);
        out.writeLong(this.key);
    }

    /** {@inheritDoc} */
    @Override
    public int getVersion() {
        return CURRENT_VERSION;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LongMerkleLeaf that)) return false;
        if (!super.equals(o)) return false;
        return key == that.key;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), key);
    }
}
