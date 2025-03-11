// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.map.account;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.serialize.KeySerializer;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This is the key serializer for the {@link AccountVirtualMapKey}.
 */
public class AccountVirtualMapKeySerializer implements KeySerializer<AccountVirtualMapKey> {

    private static final long CLASS_ID = 0x93efc6111338834eL;

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
        return AccountVirtualMapKey.getSizeInBytes();
    }

    @Override
    public void serialize(@NonNull final AccountVirtualMapKey key, @NonNull final WritableSequentialData out) {
        key.serialize(out);
    }

    @Override
    public AccountVirtualMapKey deserialize(@NonNull ReadableSequentialData in) {
        final AccountVirtualMapKey key = new AccountVirtualMapKey();
        key.deserialize(in);
        return key;
    }

    @Override
    public boolean equals(@NonNull BufferedData buffer, @NonNull AccountVirtualMapKey keyToCompare) {
        return keyToCompare.equals(buffer);
    }
}
