// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.utility.CommonUtils;

public class TestValueSerializer implements ValueSerializer<TestValue> {

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
    public int getTypicalSerializedSize() {
        return 20; // guesstimation
    }

    @Override
    public int getSerializedSize(@NonNull final TestValue data) {
        final String s = data.getValue();
        return Integer.BYTES + s.length();
    }

    @Override
    public void serialize(final TestValue data, final WritableSequentialData out) {
        final String s = data.getValue();
        final byte[] bytes = org.hiero.consensus.model.utility.CommonUtils.getNormalisedStringBytes(s);
        out.writeInt(bytes.length);
        out.writeBytes(bytes);
    }

    @Override
    public TestValue deserialize(final ReadableSequentialData in) {
        final int length = in.readInt();
        final byte[] bytes = new byte[length];
        in.readBytes(bytes);
        final String s = CommonUtils.getNormalisedStringFromBytes(bytes);
        return new TestValue(s);
    }
}
