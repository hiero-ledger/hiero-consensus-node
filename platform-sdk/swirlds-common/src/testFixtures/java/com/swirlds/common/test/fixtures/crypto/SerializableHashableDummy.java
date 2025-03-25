// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.crypto;

import java.io.IOException;
import org.hiero.consensus.model.crypto.AbstractSerializableHashable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class SerializableHashableDummy extends AbstractSerializableHashable {
    private static final long CLASS_ID = 0xeecd8387d5496ba3L;
    private static final int CLASS_VERSION = 1;
    private static final int MAX_STRING_LENGTH = 512;

    private int number;
    private String string;

    // For RuntimeConstructable
    public SerializableHashableDummy() {}

    public SerializableHashableDummy(int number, String string) {
        this.number = number;
        this.string = string;
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return CLASS_VERSION;
    }

    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeInt(number);
        out.writeNormalisedString(string);
    }

    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        number = in.readInt();
        string = in.readNormalisedString(MAX_STRING_LENGTH);
    }
}
