/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.benchmark;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

public class BenchmarkValueCodec implements Codec<BenchmarkValue> {

    public static final BenchmarkValueCodec INSTANCE = new BenchmarkValueCodec();

    @NonNull
    @Override
    public BenchmarkValue parse(
            @NonNull final ReadableSequentialData in, final boolean strictMode, final int maxDepth) {
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
