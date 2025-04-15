// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.dummy;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

public class SelfHashingDummyMerkleLeaf extends PartialMerkleLeaf implements DummyMerkleNode, MerkleLeaf {

    private static final long CLASS_ID = 0xf461c34e2c2a6375L;

    private static final int VERSION = 1;

    private static final int MAX_STRING_LENGTH = 1_024;

    private final AtomicBoolean released = new AtomicBoolean(false);

    protected String value;

    /**
     * If true then this node will intentionally return an illegal null hash. Used for debugging error pathways.
     */
    private boolean returnNullForHash;

    public SelfHashingDummyMerkleLeaf() {
        this("?");
    }

    public SelfHashingDummyMerkleLeaf(final String value) {
        this.value = value;
        this.returnNullForHash = false;
    }

    private SelfHashingDummyMerkleLeaf(final SelfHashingDummyMerkleLeaf that) {
        super(that);
        this.value = that.value;
        this.returnNullForHash = that.returnNullForHash;
    }

    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeNormalisedString(value);
    }

    @Override
    public void deserialize(final SerializableDataInputStream in, int version) throws IOException {
        value = in.readNormalisedString(MAX_STRING_LENGTH);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    public String getValue() {
        return value;
    }

    public void setValue(final String value) {
        this.value = value;
    }

    @Override
    public void destroyNode() {
        if (!released.compareAndSet(false, true)) {
            throw new IllegalStateException("This type of node should only be deleted once");
        }
    }

    @Override
    public void setHash(Hash hash) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSelfHashing() {
        return true;
    }

    @Override
    public Hash getHash() {
        if (returnNullForHash) {
            // A hash of null is not illegal, but "null" is not allowed for self hashing nodes like this one.
            return null;
        }
        return Cryptography.NULL_HASH;
    }

    public void setReturnNullForHash(final boolean returnNullForHash) {
        this.returnNullForHash = returnNullForHash;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public SelfHashingDummyMerkleLeaf copy() {
        throwIfImmutable();
        throwIfDestroyed();
        setImmutable(true);
        return new SelfHashingDummyMerkleLeaf(this);
    }

    @Override
    public int hashCode() {
        if (value == null) {
            return 0;
        } else {
            return value.hashCode();
        }
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SelfHashingDummyMerkleLeaf)) {
            return false;
        }
        SelfHashingDummyMerkleLeaf that = (SelfHashingDummyMerkleLeaf) obj;
        return Objects.equals(this.value, that.value);
    }
}
