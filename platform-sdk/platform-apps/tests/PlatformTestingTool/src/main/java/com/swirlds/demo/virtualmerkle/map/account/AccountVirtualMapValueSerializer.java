// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.map.account;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;

public class AccountVirtualMapValueSerializer implements ValueSerializer<AccountVirtualMapValue> {

    private static final long CLASS_ID = 0x7f4caa05eae90b01L;

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
        return VARIABLE_DATA_SIZE;
    }

    @Override
    public int getSerializedSize(@NonNull AccountVirtualMapValue value) {
        return value.getSizeInBytes();
    }

    @Override
    public void serialize(@NonNull final AccountVirtualMapValue value, @NonNull final WritableSequentialData out) {
        value.serialize(out);
    }

    @Override
    public AccountVirtualMapValue deserialize(@NonNull final ReadableSequentialData in) {
        final AccountVirtualMapValue value = new AccountVirtualMapValue();
        value.deserialize(in);
        return value;
    }
}
