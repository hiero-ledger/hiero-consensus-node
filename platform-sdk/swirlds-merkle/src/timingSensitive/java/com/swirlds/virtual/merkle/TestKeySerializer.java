// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtual.merkle;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.serialize.KeySerializer;

public class TestKeySerializer implements KeySerializer<TestKey> {

    public TestKeySerializer() {
        // required for deserialization
    }

    @Override
    public long getClassId() {
        return 8838921;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public int getSerializedSize() {
        return TestKey.BYTES;
    }

    @Override
    public long getCurrentDataVersion() {
        return 1;
    }

    @Override
    public void serialize(final TestKey data, final WritableSequentialData out) {
        data.serialize(out);
    }

    @Override
    public TestKey deserialize(final ReadableSequentialData in) {
        final TestKey key = new TestKey();
        key.deserialize(in);
        return key;
    }

    @Override
    public boolean equals(final BufferedData buffer, final TestKey keyToCompare) {
        return buffer.readLong() == keyToCompare.getKey();
    }
}
