// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.RandomAccessData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

public class TestValueCodec extends Codec<TestValue> {

    public static final Codec<TestValue> INSTANCE = new TestValueCodec();

    private static final TestValue DEFAULT_VALUE = new TestValue("");

    @Override
    public TestValue getDefaultInstance() {
        return DEFAULT_VALUE;
    }

    @NonNull
    @Override
    protected TestValue parseImpl(
            @NonNull ReadableSequentialData in,
            boolean strictMode,
            boolean parseUnknownFields,
            int maxDepth,
            int maxSize) {
        return new TestValue(in);
    }

    @Override
    protected void writeImpl(@NonNull TestValue value, @NonNull WritableSequentialData out) {
        value.writeTo(out);
    }

    @Override
    protected long writeImpl(@NonNull final TestValue item, @NonNull final RandomAccessData output, final long offset)
            throws IOException {
        return output.putBytes(offset, toBytes(item));
    }

    @Override
    public int measureRecord(TestValue value) {
        return value.getSizeInBytes();
    }
}
