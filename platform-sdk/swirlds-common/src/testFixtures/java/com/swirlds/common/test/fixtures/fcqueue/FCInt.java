// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.fcqueue;

import com.swirlds.common.FastCopyable;
import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.crypto.AbstractSerializableHashable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/** a FastCopyable class that does nothing but store a single 4-byte int */
public class FCInt extends AbstractSerializableHashable implements FastCopyable {
    private static final long CLASS_ID = 0xe044c97abd231f8fL;
    private static final int CLASS_VERSION = 2;

    private int value;

    private boolean immutable;

    public FCInt() {}

    public FCInt(final int value) {
        this.value = value;
    }

    private FCInt(final FCInt value) {
        this.value = value.value;
        this.immutable = false;
        value.immutable = true;
    }

    public int getValue() {
        return value;
    }

    public void setValue(final int value) {
        this.value = value;
    }

    @Override
    public FCInt copy() {
        throwIfImmutable();
        return new FCInt(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeInt(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        value = in.readInt();
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
        return CLASS_VERSION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isImmutable() {
        return this.immutable;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.value);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        FCInt that = (FCInt) obj;
        return this.value == that.value;
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}
