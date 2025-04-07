// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class TransactionHash extends PartialMerkleLeaf implements MerkleLeaf {

    private static final long CLASS_ID = 0x49fd3cbce17c58fL;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
        public static final int MIGRATE_TO_SERIALIZABLE = 2;
    }

    private static final int MAX_HASH_LENGTH = 512;

    private byte[] hash;

    public TransactionHash() {}

    private TransactionHash(final byte[] hash) {
        this.hash = hash;
    }

    private TransactionHash(final TransactionHash transactionHash) {
        super(transactionHash);
        this.hash = Arrays.copyOf(transactionHash.hash, transactionHash.hash.length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TransactionHash copy() {
        throwIfImmutable();
        return new TransactionHash(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeByteArray(this.hash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        this.hash = in.readByteArray(MAX_HASH_LENGTH);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof TransactionHash)) {
            return false;
        }

        final TransactionHash that = (TransactionHash) o;
        return Arrays.equals(hash, that.hash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(hash);
    }

    static TransactionHash generateRandom(final Random random) {
        final byte[] hash = new byte[48];
        random.nextBytes(hash);
        return new TransactionHash(hash);
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
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }
}
