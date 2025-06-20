// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.map.smartcontracts.data;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;

/** This class is the serializer of {@link SmartContractMapValue}. */
public final class SmartContractMapValueSerializer implements ValueSerializer<SmartContractMapValue> {

    // Serializer class ID
    private static final long CLASS_ID = 0xed6c1e1f0b6bda21L;

    // Serializer version
    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    // Value data version
    private static final int DATA_VERSION = 1;

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
        return DATA_VERSION;
    }

    @Override
    public int getSerializedSize() {
        return SmartContractMapValue.getSizeInBytes();
    }

    @Override
    public void serialize(@NonNull final SmartContractMapValue value, @NonNull final WritableSequentialData out) {
        value.serialize(out);
    }

    @Override
    public SmartContractMapValue deserialize(@NonNull final ReadableSequentialData in) {
        final SmartContractMapValue value = new SmartContractMapValue();
        value.deserialize(in);
        return value;
    }
}
