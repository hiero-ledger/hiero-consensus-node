// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class TestValue {

    private String s;

    public TestValue(String s) {
        this.s = s;
    }

    public TestValue(final ReadableSequentialData in) {
        final int len = in.readInt();
        final byte[] bytes = new byte[len];
        in.readBytes(bytes);
        this.s = new String(bytes, StandardCharsets.UTF_8);
    }

    public String getValue() {
        return s;
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
}
