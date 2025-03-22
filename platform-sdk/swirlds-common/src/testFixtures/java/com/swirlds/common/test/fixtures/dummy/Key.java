// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.dummy;

import com.swirlds.common.FastCopyable;
import java.io.IOException;
import java.util.Arrays;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class Key implements SelfSerializable, FastCopyable {

    private static final long CLASS_ID = 0xc7be56d110643716L;

    private static final int SHARD_ID_INDEX = 0;
    private static final int REALM_ID_INDEX = 1;
    private static final int ACCOUNT_ID_INDEX = 2;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private static final int ACCOUNT_SIZE = 3;

    protected long[] accountIds;

    public Key(final long shardId, final long realmId, final long accountId) {
        this();
        this.accountIds[SHARD_ID_INDEX] = shardId;
        this.accountIds[REALM_ID_INDEX] = realmId;
        this.accountIds[ACCOUNT_ID_INDEX] = accountId;
    }

    public Key(final long[] accountIds) {
        this(accountIds[SHARD_ID_INDEX], accountIds[REALM_ID_INDEX], accountIds[ACCOUNT_ID_INDEX]);
    }

    private Key(final Key key) {
        this.accountIds = new long[ACCOUNT_SIZE];
        this.accountIds[SHARD_ID_INDEX] = key.accountIds[SHARD_ID_INDEX];
        this.accountIds[REALM_ID_INDEX] = key.accountIds[REALM_ID_INDEX];
        this.accountIds[ACCOUNT_ID_INDEX] = key.accountIds[ACCOUNT_ID_INDEX];
    }

    public Key() {
        this.accountIds = new long[ACCOUNT_SIZE];
    }

    @Override
    public int hashCode() {
        final int PRIME = 31;
        int result = 1;
        result = PRIME * result + Arrays.hashCode(accountIds);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null) {
            return false;
        }

        if (!(obj instanceof Key)) {
            return false;
        }

        final Key other = (Key) obj;
        return Arrays.equals(accountIds, other.accountIds);
    }

    @Override
    public String toString() {
        return Arrays.toString(this.accountIds);
    }

    public Key copy() {
        return new Key(this);
    }

    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    @Override
    public void serialize(final SerializableDataOutputStream outStream) throws IOException {
        for (int i = 0; i < ACCOUNT_SIZE; i++) {
            outStream.writeLong(accountIds[i]);
        }
    }

    @Override
    public void deserialize(final SerializableDataInputStream inputStream, final int version) throws IOException {
        for (int index = 0; index < ACCOUNT_SIZE; index++) {
            accountIds[index] = inputStream.readLong();
        }
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }
}
