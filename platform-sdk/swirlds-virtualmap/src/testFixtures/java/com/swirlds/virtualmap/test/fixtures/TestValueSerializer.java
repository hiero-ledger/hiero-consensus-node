// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;

public class TestValueSerializer implements ValueSerializer<TestValue> {

    public static final TestValueSerializer INSTANCE = new TestValueSerializer();

    @Override
    public long getClassId() {
        return 0x51c8d5d21f7125e8L;
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
    public int getSerializedSize(@NonNull final TestValue data) {
        final String value = data.getValue();
        return Integer.BYTES + value.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public int getTypicalSerializedSize() {
        return 32;
    }

    @Override
    public void serialize(@NonNull final TestValue data, @NonNull final WritableSequentialData out) {
        final String value = data.getValue();
        final byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(valueBytes.length);
        out.writeBytes(valueBytes);
    }

    @Override
    public TestValue deserialize(@NonNull ReadableSequentialData in) {
        final int length = in.readInt();
        final byte[] valueBytes = new byte[length];
        in.readBytes(valueBytes);
        final String value = new String(valueBytes, StandardCharsets.UTF_8);
        return new TestValue(value);
    }
}
