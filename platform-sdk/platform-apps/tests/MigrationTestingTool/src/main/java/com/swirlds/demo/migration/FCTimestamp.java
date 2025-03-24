// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import java.io.IOException;
import java.util.Objects;
import java.util.Random;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public final class FCTimestamp extends PartialMerkleLeaf implements MerkleLeaf {

    private static final long CLASS_ID = 0x72e33dd23a6e9f05L;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
        public static final int MIGRATE_TO_SERIALIZABLE = 2;
    }

    private long seconds;
    private int nano;

    public FCTimestamp() {}

    private FCTimestamp(final long seconds, final int nano) {
        this.seconds = seconds;
        this.nano = nano;
    }

    private FCTimestamp(final FCTimestamp timestamp) {
        super(timestamp);
        this.seconds = timestamp.seconds;
        this.nano = timestamp.nano;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FCTimestamp copy() {
        throwIfImmutable();
        return new FCTimestamp(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        if (isDestroyed()) {
            throw new IllegalStateException("Trying to serialize a deleted FCTimestamp");
        }
        out.writeLong(this.seconds);
        out.writeInt(this.nano);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        this.seconds = in.readLong();
        this.nano = in.readInt();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof FCTimestamp)) {
            return false;
        }

        final FCTimestamp that = (FCTimestamp) o;
        return seconds == that.seconds && nano == that.nano;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(seconds, nano);
    }

    /**
     * Generates a random FCTimestamp based on a seed
     *
     * @return Random seed-based FCTimestamp
     */
    static FCTimestamp generateRandom(final Random random) {
        final long seconds = random.nextLong();
        final int nano = random.nextInt();
        return new FCTimestamp(seconds, nano);
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
