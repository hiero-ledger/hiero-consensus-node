// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.swirlds.virtualmap.serialize.ValueSerializer;

public final class ExampleFixedSizeVirtualValueSerializer implements ValueSerializer<ExampleFixedSizeVirtualValue> {

    public ExampleFixedSizeVirtualValueSerializer() {}

    // SelfSerializable

    private static final long CLASS_ID = 0x954027b17b5b54afL;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    // ValueSerializer

    @Override
    public long getCurrentDataVersion() {
        return ExampleFixedSizeVirtualValue.SERIALIZATION_VERSION;
    }

    @Override
    public int getSerializedSize() {
        return Integer.BYTES + ExampleFixedSizeVirtualValue.RANDOM_BYTES;
    }

    @Override
    public void serialize(final ExampleFixedSizeVirtualValue data, final WritableSequentialData out) {
        out.writeInt(data.getId());
        out.writeBytes(data.getData());
    }

    @Override
    public ExampleFixedSizeVirtualValue deserialize(final ReadableSequentialData in) {
        final int id = in.readInt();
        final byte[] bytes = new byte[ExampleFixedSizeVirtualValue.RANDOM_BYTES];
        in.readBytes(bytes);
        return new ExampleFixedSizeVirtualValue(id, bytes);
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(final Object obj) {
        return obj instanceof ExampleFixedSizeVirtualValueSerializer;
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return (int) CLASS_ID;
    }
}
