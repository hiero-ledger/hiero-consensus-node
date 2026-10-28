// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

public class BenchmarkValueCodec extends Codec<BenchmarkValue> {

    public static final BenchmarkValueCodec INSTANCE = new BenchmarkValueCodec();

    @Override
    public BenchmarkValue getDefaultInstance() {
        // This method is not used in benchmarks
        return null;
    }

    @NonNull
    @Override
    protected BenchmarkValue parseImpl(
            @NonNull final ReadableSequentialData in,
            final boolean strictMode,
            final boolean parseUnknownFields,
            final int maxDepth,
            final int maxSize) {
        return new BenchmarkValue(in);
    }

    @Override
    protected void writeImpl(@NonNull final BenchmarkValue value, @NonNull final WritableSequentialData out)
            throws IOException {
        value.writeTo(out);
    }

    @Override
    public int measureRecord(final BenchmarkValue value) {
        return value.getSizeInBytes();
    }
}
