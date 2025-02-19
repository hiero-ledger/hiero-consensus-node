/*
 * Copyright (C) 2016-2025 Hedera Hashgraph, LLC
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

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.swirlds.virtualmap.serialize.BaseSerializer;

public class BenchmarkRecordSerializer implements BaseSerializer<BenchmarkRecord> {

    @Override
    public int getSerializedSize() {
        return VARIABLE_DATA_SIZE;
    }

    @Override
    public int getSerializedSize(final BenchmarkRecord record) {
        return record.getSizeInBytes();
    }

    @Override
    public long getCurrentDataVersion() {
        return BenchmarkValue.VERSION;
    }

    @Override
    public void serialize(final BenchmarkRecord data, final WritableSequentialData out) {
        data.serialize(out);
    }

    @Override
    public BenchmarkRecord deserialize(final ReadableSequentialData in) {
        BenchmarkRecord data = new BenchmarkRecord();
        data.deserialize(in);
        return data;
    }
}
