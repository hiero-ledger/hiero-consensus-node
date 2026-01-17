// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

public class BenchmarkValueCodec implements Codec<BenchmarkValue> {

    public static final BenchmarkValueCodec INSTANCE = new BenchmarkValueCodec();

    @Override
    public BenchmarkValue getDefaultInstance() {
        // This method is not used in benchmarks
        return null;
    }

    @NonNull
    @Override
    public BenchmarkValue parse(
            @NonNull final ReadableSequentialData in,
            final boolean strictMode,
            final boolean parseUnknownFields,
            final int maxDepth,
            final int maxSize) {
        return new BenchmarkValue(in);
    }

    @Override
    public void write(@NonNull final BenchmarkValue value, @NonNull final WritableSequentialData out)
            throws IOException {
        value.writeTo(out);
    }

    @Override
    public int measure(@NonNull final ReadableSequentialData in) {
        throw new UnsupportedOperationException("BenchmarkValueCodec.measure() not implemented");
    }

    @Override
    public int measureRecord(final BenchmarkValue value) {
        return value.getSizeInBytes();
    }

    @Override
    public boolean fastEquals(@NonNull final BenchmarkValue value, @NonNull final ReadableSequentialData in)
            throws ParseException {
        // It can be implemented in a more efficient way, but is it really used in benchmarks?
        final BenchmarkValue other = parse(in);
        return other.equals(value);
    }
}
