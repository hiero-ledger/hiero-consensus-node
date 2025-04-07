// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.io;

import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class SelfSerializableExample implements SelfSerializable {
    private static final long CLASS_ID = 0x157bddfb3b8a7c75L;
    private int CLASS_VERSION = 1;
    private static final int STRING_MAX_BYTES = 512;

    private int aNumber;
    private String aString;

    // no args constructor required for RuntimeConstructable
    public SelfSerializableExample() {}

    public SelfSerializableExample(int aNumber, String aString) {
        this.aNumber = aNumber;
        this.aString = aString;
    }

    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        this.aNumber = in.readInt();
        this.aString = in.readNormalisedString(STRING_MAX_BYTES);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return CLASS_VERSION;
    }

    /**
     * Set the version. For tests that intentionally want to break things with invalid versions.
     */
    public void setVersion(int CLASS_VERSION) {
        this.CLASS_VERSION = CLASS_VERSION;
    }

    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeInt(aNumber);
        out.writeNormalisedString(aString);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SelfSerializableExample that = (SelfSerializableExample) o;
        return aNumber == that.aNumber && Objects.equals(aString, that.aString);
    }

    @Override
    public String toString() {
        return "SerializableDetExample{" + "aNumber=" + aNumber + ", aString='" + aString + '\'' + '}';
    }
}
