// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.utility;

import com.swirlds.common.FastCopyable;
import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A long that is serializable.
 */
public class SerializableLong implements Comparable<SerializableLong>, FastCopyable, SelfSerializable {

    public static final long CLASS_ID = 0x70deca6058a40bc6L;

    private static class ClassVersion {

        public static final int ORIGINAL = 1;
    }

    private long value;

    /**
     * Create a new SerializableLong and set its value.
     *
     * @param value
     * 		the value for this object
     */
    public SerializableLong(final long value) {
        this.value = value;
    }

    /**
     * Create a new SerializableLong with a value of 0.
     */
    public SerializableLong() {}

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    public SerializableLong copy() {
        return new SerializableLong(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(final SerializableLong that) {
        return Long.compare(this.value, that.value);
    }

    /**
     * Get the value.
     *
     * @return the value
     */
    public long getValue() {
        return this.value;
    }

    /**
     * Increment the value and return the result.
     *
     * @return the resulting value
     */
    public long getAndIncrement() {
        return value++;
    }

    /**
     * Decrement the value and return it.
     *
     * @return the resulting value
     */
    public long getAndDecrement() {
        return value--;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeLong(this.value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        this.value = in.readLong();
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
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof SerializableLong)) {
            return false;
        }

        final SerializableLong that = (SerializableLong) o;
        return value == that.value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return Long.toString(value);
    }
}
