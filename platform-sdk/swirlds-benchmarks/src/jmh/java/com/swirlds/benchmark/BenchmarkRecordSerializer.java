// SPDX-License-Identifier: Apache-2.0
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
