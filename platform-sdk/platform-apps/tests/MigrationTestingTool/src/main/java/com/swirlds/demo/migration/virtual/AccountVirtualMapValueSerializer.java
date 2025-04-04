// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration.virtual;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A self serializable supplier for AccountVirtualMapValue.
 */
public class AccountVirtualMapValueSerializer implements ValueSerializer<AccountVirtualMapValue> {

    private static final long CLASS_ID = 0x7f4caa05eae90b01L;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getCurrentDataVersion() {
        return 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getSerializedSize() {
        return VARIABLE_DATA_SIZE;
    }

    @Override
    public int getSerializedSize(@NonNull AccountVirtualMapValue value) {
        return value.getSizeInBytes();
    }

    @Override
    public void serialize(final AccountVirtualMapValue value, final WritableSequentialData out) {
        value.serialize(out);
    }

    @Override
    public AccountVirtualMapValue deserialize(final ReadableSequentialData in) {
        final AccountVirtualMapValue value = new AccountVirtualMapValue();
        value.deserialize(in);
        return value;
    }
}
