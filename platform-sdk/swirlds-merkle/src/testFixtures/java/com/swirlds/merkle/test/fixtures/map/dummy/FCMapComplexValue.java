// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.dummy;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.impl.PartialNaryMerkleInternal;
import com.swirlds.common.merkle.utility.Keyed;
import com.swirlds.common.merkle.utility.MerkleLong;
import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.fcqueue.FCQueue;
import java.util.Objects;

/**
 * This merkle node is designed to test FCMap to MerkleMap migration.
 */
public class FCMapComplexValue extends PartialNaryMerkleInternal implements Keyed<SerializableLong>, MerkleInternal {

    private static final long CLASS_ID = 0xea7f1d984a6b3d23L;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private static class ChildIndices {
        public static final int MERKLE_LONG = 0;
        public static final int FC_QUEUE = 1;
        public static final int KEY = 2;

        public static final int CHILD_COUNT = 3;
    }

    public FCMapComplexValue() {}

    /**
     * Copy constructor.
     */
    private FCMapComplexValue(final FCMapComplexValue that) {
        setMerkleLong(that.getMerkleLong().copy());
        setFCQueue(that.getFCQueue().copy());
        setKey(that.getKey());

        that.setImmutable(true);
    }

    /**
     * Get the nested merkle long.
     */
    public MerkleLong getMerkleLong() {
        return getChild(ChildIndices.MERKLE_LONG);
    }

    /**
     * Set the nested merkle long.
     */
    public void setMerkleLong(final MerkleLong merkleLong) {
        setChild(ChildIndices.MERKLE_LONG, merkleLong);
    }

    /**
     * Get the nested FCQueue.
     */
    public FCQueue<DummyFCQueueElement> getFCQueue() {
        return getChild(ChildIndices.FC_QUEUE);
    }

    /**
     * Set the nested FCQueue.
     */
    public void setFCQueue(final FCQueue<DummyFCQueueElement> fcQueue) {
        setChild(ChildIndices.FC_QUEUE, fcQueue);
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
    public FCMapComplexValue copy() {
        return new FCMapComplexValue(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SerializableLong getKey() {
        final MerkleLong merkleKey = getChild(ChildIndices.KEY);
        if (merkleKey == null) {
            return null;
        }
        return new SerializableLong(merkleKey.getValue());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setKey(final SerializableLong key) {
        if (key == null) {
            setChild(ChildIndices.KEY, null);
        } else {
            setChild(ChildIndices.KEY, new MerkleLong(key.getValue()));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        }

        if (!(obj instanceof final FCMapComplexValue that)) {
            return false;
        }

        return Objects.equals(getMerkleLong(), that.getMerkleLong()) && Objects.equals(getFCQueue(), that.getFCQueue());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(getMerkleLong(), getFCQueue());
    }
}
