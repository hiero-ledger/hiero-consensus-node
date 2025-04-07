// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.swirlds.virtualmap.VirtualKey;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public final class TestKey implements VirtualKey {

    private long k;

    private static final long CLASS_ID = 0x5491740ab996d4b1L;

    public TestKey() {}

    public TestKey(long path) {
        this.k = path;
    }

    public TestKey(char s) {
        this.k = s;
    }

    public TestKey copy() {
        return new TestKey(k);
    }

    @Override
    public int getVersion() {
        return 1;
    }

    long getKey() {
        return k;
    }

    void serialize(final ByteBuffer buffer) {
        buffer.putLong(k);
    }

    void deserialize(final ByteBuffer buffer) {
        k = buffer.getLong();
    }

    @Override
    public int hashCode() {
        return Long.hashCode(k);
    }

    @Override
    public String toString() {
        if (Character.isAlphabetic((char) k)) {
            return "TestKey{ " + ((char) k) + " }";
        } else {
            return "TestKey{ " + k + " }";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestKey other = (TestKey) o;
        return k == other.k;
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeLong(k);
    }

    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        k = in.readLong();
    }
}
