// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtual.merkle;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.serialize.KeySerializer;

public class TestObjectKeySerializer implements KeySerializer<TestObjectKey> {

    public TestObjectKeySerializer() {
        // required for deserialization
    }

    @Override
    public long getClassId() {
        return 8838922;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public int getSerializedSize() {
        return TestObjectKey.BYTES;
    }

    @Override
    public long getCurrentDataVersion() {
        return 1;
    }

    @Override
    public void serialize(final TestObjectKey data, final WritableSequentialData out) {
        data.serialize(out);
    }

    @Override
    public TestObjectKey deserialize(final ReadableSequentialData in) {
        final TestObjectKey key = new TestObjectKey();
        key.deserialize(in);
        return key;
    }

    @Override
    public boolean equals(final BufferedData buffer, final TestObjectKey keyToCompare) {
        return (buffer.readLong() == keyToCompare.getValue()) && (buffer.readLong() == keyToCompare.getValue());
    }
}
