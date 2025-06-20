// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.serialize.KeySerializer;
import edu.umd.cs.findbugs.annotations.NonNull;

public class TestKeySerializer implements KeySerializer<TestKey> {

    public static final TestKeySerializer INSTANCE = new TestKeySerializer();

    @Override
    public long getClassId() {
        return 0x592a33a2329ec4b9L;
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
    public int getSerializedSize(@NonNull TestKey data) {
        return Long.BYTES;
    }

    @Override
    public int getTypicalSerializedSize() {
        return Long.BYTES;
    }

    @Override
    public void serialize(@NonNull final TestKey data, @NonNull final WritableSequentialData out) {
        out.writeLong(data.getKey());
    }

    @Override
    public TestKey deserialize(@NonNull final ReadableSequentialData in) {
        final long key = in.readLong();
        return new TestKey(key);
    }

    @Override
    public boolean equals(@NonNull final BufferedData buffer, @NonNull final TestKey keyToCompare) {
        return buffer.readLong() == keyToCompare.getKey();
    }
}
