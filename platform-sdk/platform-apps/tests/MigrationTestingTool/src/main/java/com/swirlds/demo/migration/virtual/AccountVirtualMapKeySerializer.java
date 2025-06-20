// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration.virtual;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.serialize.KeySerializer;

/**
 * This is the key serializer for the {@link AccountVirtualMapKey}.
 */
public class AccountVirtualMapKeySerializer implements KeySerializer<AccountVirtualMapKey> {

    private static final long CLASS_ID = 0x93efc6111338834eL;

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
    public int getSerializedSize() {
        return AccountVirtualMapKey.getSizeInBytes();
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
    public void serialize(final AccountVirtualMapKey key, final WritableSequentialData out) {
        key.serialize(out);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AccountVirtualMapKey deserialize(final ReadableSequentialData in) {
        final AccountVirtualMapKey key = new AccountVirtualMapKey();
        key.deserialize(in);
        return key;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final BufferedData buffer, final AccountVirtualMapKey keyToCompare) {
        return keyToCompare.equals(buffer);
    }
}
