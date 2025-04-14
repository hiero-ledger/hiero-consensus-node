// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtual.merkle;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import org.hiero.base.utility.CommonUtils;

public class TestValueSerializer implements ValueSerializer<TestValue> {

    public TestValueSerializer() {
        // required for deserialization
    }

    @Override
    public long getClassId() {
        return 53543454;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public long getCurrentDataVersion() {
        return 1;
    }

    @Override
    public int getSerializedSize() {
        return VARIABLE_DATA_SIZE;
    }

    @Override
    public int getSerializedSize(final TestValue data) {
        final byte[] bytes = CommonUtils.getNormalisedStringBytes(data.getValue());
        return Integer.BYTES + bytes.length;
    }

    @Override
    public int getTypicalSerializedSize() {
        return 20; // guesstimation
    }

    @Override
    public void serialize(final TestValue data, final WritableSequentialData out) {
        final byte[] bytes = CommonUtils.getNormalisedStringBytes(data.getValue());
        out.writeInt(bytes.length);
        out.writeBytes(bytes);
    }

    @Override
    public TestValue deserialize(final ReadableSequentialData in) {
        final int size = in.readInt();
        final byte[] bytes = new byte[size];
        in.readBytes(bytes);
        final String value = CommonUtils.getNormalisedStringFromBytes(bytes);
        return new TestValue(value);
    }
}
