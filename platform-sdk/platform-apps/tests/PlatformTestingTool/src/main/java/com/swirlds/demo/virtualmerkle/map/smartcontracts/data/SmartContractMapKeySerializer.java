// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.map.smartcontracts.data;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.serialize.KeySerializer;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This class is the serializer for {@link SmartContractMapKey}.
 */
public final class SmartContractMapKeySerializer implements KeySerializer<SmartContractMapKey> {

    private static final long CLASS_ID = 0x2d68463768cf4c5AL;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    @Override
    public long getCurrentDataVersion() {
        return 1;
    }

    @Override
    public int getSerializedSize() {
        return SmartContractMapKey.getSizeInBytes();
    }

    @Override
    public void serialize(@NonNull final SmartContractMapKey key, @NonNull final WritableSequentialData out) {
        key.serialize(out);
    }

    @Override
    public SmartContractMapKey deserialize(@NonNull final ReadableSequentialData in) {
        final SmartContractMapKey key = new SmartContractMapKey();
        key.deserialize(in);
        return key;
    }

    @Override
    public boolean equals(@NonNull final BufferedData buffer, @NonNull final SmartContractMapKey keyToCompare) {
        return keyToCompare.equals(buffer);
    }
}
