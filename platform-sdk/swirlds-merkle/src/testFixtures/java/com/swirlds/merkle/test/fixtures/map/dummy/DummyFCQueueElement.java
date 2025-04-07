// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.dummy;

import com.swirlds.common.FastCopyable;
import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.crypto.SerializableHashable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * An element that sits in an FCQueue
 */
public class DummyFCQueueElement implements FastCopyable, SerializableHashable {

    private static final long CLASS_ID = 0x1fc41d4f294c4115L;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private long value;
    private Hash hash;

    public DummyFCQueueElement() {}

    public DummyFCQueueElement(final long value) {
        this.value = value;
    }

    private DummyFCQueueElement(final DummyFCQueueElement that) {
        this.value = that.value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FastCopyable copy() {
        return new DummyFCQueueElement(this);
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
    public Hash getHash() {
        return hash;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setHash(final Hash hash) {
        this.hash = hash;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeLong(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        value = in.readLong();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    /**
     * Get the value.
     */
    public long getValue() {
        return value;
    }

    /**
     * Set the value.
     */
    public void setValue(final long value) {
        this.value = value;
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
        final DummyFCQueueElement that = (DummyFCQueueElement) o;
        return value == that.value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
