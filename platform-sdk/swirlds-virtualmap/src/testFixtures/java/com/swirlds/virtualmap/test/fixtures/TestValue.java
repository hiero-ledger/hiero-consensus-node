// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class TestValue {

    private String s;

    public TestValue(long path) {
        this("Value " + path);
    }

    public TestValue(String s) {
        this.s = s;
    }

    public TestValue(ReadableSequentialData in) {
        final int len = in.readInt();
        final byte[] value = new byte[len];
        in.readBytes(value);
        this.s = new String(value, StandardCharsets.UTF_8);
    }

    public int getSizeInBytes() {
        final byte[] value = s.getBytes(StandardCharsets.UTF_8);
        return Integer.BYTES + value.length;
    }

    public void writeTo(final WritableSequentialData out) {
        final byte[] value = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(value.length);
        out.writeBytes(value);
    }

    public Bytes toBytes() {
        final byte[] bytes = new byte[getSizeInBytes()];
        writeTo(BufferedData.wrap(bytes));
        return Bytes.wrap(bytes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestValue other = (TestValue) o;
        return Objects.equals(s, other.s);
    }

    public String getValue() {
        return s;
    }

    @Override
    public int hashCode() {
        return Objects.hash(s);
    }

    @Override
    public String toString() {
        return "TestValue{ " + s + " }";
    }
}
