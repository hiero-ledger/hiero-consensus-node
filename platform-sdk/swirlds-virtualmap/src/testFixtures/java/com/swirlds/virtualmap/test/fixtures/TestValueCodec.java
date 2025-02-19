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

package com.swirlds.virtualmap.test.fixtures;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;

public class TestValueCodec implements Codec<TestValue> {

    public static final TestValueCodec INSTANCE = new TestValueCodec();

    @NonNull
    @Override
    public TestValue parse(@NonNull ReadableSequentialData in, boolean strictMode, int maxDepth) throws ParseException {
        return new TestValue(in);
    }

    @Override
    public void write(@NonNull TestValue value, @NonNull WritableSequentialData out) {
        value.writeTo(out);
    }

    @Override
    public int measure(@NonNull ReadableSequentialData in) throws ParseException {
        throw new UnsupportedOperationException("TestValueCodec.measure() not implemented");
    }

    @Override
    public int measureRecord(TestValue value) {
        return value.getSizeInBytes();
    }

    @Override
    public boolean fastEquals(@NonNull TestValue value, @NonNull ReadableSequentialData in) throws ParseException {
        final TestValue other = parse(in);
        return other.equals(value);
    }
}
