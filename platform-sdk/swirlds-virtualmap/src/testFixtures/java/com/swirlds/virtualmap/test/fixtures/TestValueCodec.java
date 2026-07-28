// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.PbjReader;
import com.hedera.pbj.runtime.io.PbjWriter;
import edu.umd.cs.findbugs.annotations.NonNull;

public class TestValueCodec implements Codec<TestValue> {

    public static final Codec<TestValue> INSTANCE = new TestValueCodec();

    private static final TestValue DEFAULT_VALUE = new TestValue("");

    @Override
    public TestValue getDefaultInstance() {
        return DEFAULT_VALUE;
    }

    @NonNull
    @Override
    public TestValue realParse(
            @NonNull PbjReader in, boolean strictMode, boolean parseUnknownFields, int maxDepth, int maxSize) {
        return new TestValue(in);
    }

    @Override
    public void realWrite(@NonNull TestValue value, @NonNull PbjWriter out) {
        value.writeTo(out);
    }

    @Override
    public int measure(@NonNull PbjReader in) {
        throw new UnsupportedOperationException("TestValueCodec.measure() not implemented");
    }

    @Override
    public int measureRecord(TestValue value) {
        return value.getSizeInBytes();
    }

    @Override
    public boolean fastEquals(@NonNull TestValue value, @NonNull PbjReader in) throws ParseException {
        final TestValue other = parse(in);
        return other.equals(value);
    }
}
