// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.swirlds.virtualmap.serialize.ValueSerializer;

public class BenchmarkValueSerializer implements ValueSerializer<BenchmarkValue> {

    // Serializer class ID
    private static final long CLASS_ID = 0xbae262725c67b901L;

    // Serializer version
    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    // Value data version
    private static final int DATA_VERSION = 1;

    public BenchmarkValueSerializer() {
        // required for deserialization
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    @Override
    public long getCurrentDataVersion() {
        return DATA_VERSION;
    }

    @Override
    public int getSerializedSize() {
        return Integer.BYTES + BenchmarkValue.getValueSize();
    }

    @Override
    public void serialize(final BenchmarkValue data, final WritableSequentialData out) {
        data.serialize(out);
    }

    @Override
    public BenchmarkValue deserialize(final ReadableSequentialData in) {
        final BenchmarkValue value = new BenchmarkValue();
        value.deserialize(in);
        return value;
    }
}
