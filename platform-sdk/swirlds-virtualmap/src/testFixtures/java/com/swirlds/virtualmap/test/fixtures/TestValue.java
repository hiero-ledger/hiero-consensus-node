// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.swirlds.virtualmap.VirtualValue;
import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public final class TestValue implements VirtualValue {

    private String s;
    public boolean readOnly = false;
    private boolean released = false;

    public TestValue() {}

    public TestValue(long path) {
        this("Value " + path);
    }

    public TestValue(String s) {
        this.s = s;
    }

    @Override
    public long getClassId() {
        return 0x155bb9565ebfad3aL;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    String getValue() {
        return s;
    }

    public void setValue(String s) {
        assertMutable("setValue");
        assertNotReleased("setValue");
        this.s = s;
    }

    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        assertNotReleased("serialize");
        out.writeNormalisedString(s);
    }

    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        assertNotReleased("deserialize");
        assertMutable("deserialize");
        s = in.readNormalisedString(1024);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestValue other = (TestValue) o;
        return Objects.equals(s, other.s);
    }

    public String value() {
        assertNotReleased("fetch value");
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

    @Override
    public TestValue copy() {
        assertNotReleased("copy");
        readOnly = true;
        return new TestValue(s);
    }

    @Override
    public VirtualValue asReadOnly() {
        assertNotReleased("make readonly copy");
        TestValue value = new TestValue(s);
        value.readOnly = true;
        return value;
    }

    @Override
    public boolean release() {
        assertNotReleased("release");
        released = true;
        return true;
    }

    private void assertMutable(String action) {
        throwIfImmutable("Trying to " + action + " when already immutable.");
    }

    private void assertNotReleased(String action) {
        throwIfDestroyed("Trying to " + action + " when released.");
    }

    @Override
    public boolean isDestroyed() {
        return released;
    }

    @Override
    public boolean isImmutable() {
        return readOnly;
    }
}
