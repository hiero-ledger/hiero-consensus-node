// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.dummy;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import java.io.IOException;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A merkle wrapper for {@link Key}.
 */
public class MerkleKey extends PartialMerkleLeaf implements MerkleLeaf {

    private Key key;

    public static final long CLASS_ID = 0x7183149501d8201dL;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    public MerkleKey() {}

    public MerkleKey(final Key key) {
        this.key = key;
    }

    private MerkleKey(final MerkleKey that) {
        super(that);
        this.key = that.key;
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
        out.writeSerializable(key, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        key = in.readSerializable(false, Key::new);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleKey copy() {
        return new MerkleKey(this);
    }

    /**
     * Get the key.
     */
    public Key getKey() {
        return key;
    }
}
