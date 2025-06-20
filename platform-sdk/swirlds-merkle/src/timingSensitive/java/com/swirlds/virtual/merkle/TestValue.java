// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtual.merkle;

import com.swirlds.virtualmap.VirtualValue;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.base.utility.CommonUtils;

public final class TestValue implements VirtualValue {

    private String s;

    public TestValue() {}

    public TestValue(long path) {
        this("Value " + path);
    }

    public TestValue(String s) {
        this.s = s;
    }

    public String getValue() {
        return s;
    }

    @Override
    public long getClassId() {
        return 0x155bb9565ebfad3aL;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    int sizeInBytes() {
        final byte[] data = CommonUtils.getNormalisedStringBytes(s);
        return Integer.BYTES + data.length;
    }

    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeNormalisedString(s);
    }

    void serialize(ByteBuffer buffer) {
        final byte[] data = CommonUtils.getNormalisedStringBytes(this.s);
        buffer.putInt(data.length);
        buffer.put(data);
    }

    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        s = in.readNormalisedString(1024);
    }

    void deserialize(ByteBuffer buffer, int version) {
        final int length = buffer.getInt();
        if (length > 1024) {
            throw new IllegalStateException("Bad data from buffer for string length. Value: " + length);
        }
        final byte[] data = new byte[length];
        buffer.get(data, 0, length);
        this.s = CommonUtils.getNormalisedStringFromBytes(data);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestValue other = (TestValue) o;
        return Objects.equals(s, other.s);
    }

    @Override
    public int hashCode() {
        return Objects.hash(s);
    }

    @Override
    public String toString() {
        return "TestValue{ " + s + " }";
    }

    @Override
    public TestValue copy() {
        return new TestValue(s);
    }

    @Override
    public VirtualValue asReadOnly() {
        return this; // No setters on this thing, just don't deserialize...
    }
}
