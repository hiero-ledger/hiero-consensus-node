// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.serialize.KeySerializer;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This class is the serializer of {@link SmartContractByteCodeMapKey}.
 */
public final class SmartContractByteCodeMapKeySerializer implements KeySerializer<SmartContractByteCodeMapKey> {

    private static final long CLASS_ID = 0xee36c20c7ccc69daL;

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
        return SmartContractByteCodeMapKey.getSizeInBytes();
    }

    @Override
    public void serialize(@NonNull final SmartContractByteCodeMapKey key, @NonNull final WritableSequentialData out) {
        key.serialize(out);
    }

    @Override
    public SmartContractByteCodeMapKey deserialize(@NonNull final ReadableSequentialData in) {
        final SmartContractByteCodeMapKey key = new SmartContractByteCodeMapKey();
        key.deserialize(in);
        return key;
    }

    @Override
    public boolean equals(@NonNull final BufferedData buffer, @NonNull final SmartContractByteCodeMapKey keyToCompare) {
        return keyToCompare.equals(buffer);
    }
}
