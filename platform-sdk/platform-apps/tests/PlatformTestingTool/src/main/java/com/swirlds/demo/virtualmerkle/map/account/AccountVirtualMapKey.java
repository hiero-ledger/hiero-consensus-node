// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.map.account;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.VirtualKey;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * This class represents the key to find an account that is being
 * stored inside a {@link com.swirlds.virtualmap.VirtualMap} instance.
 */
public class AccountVirtualMapKey implements VirtualKey {
    private static final long CLASS_ID = 0xff95b64a8d311cdaL;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private long realmID;
    private long shardID;
    private long accountID;

    public AccountVirtualMapKey() {
        this(0, 0, 0);
    }

    public AccountVirtualMapKey(final long realmID, final long shardID, final long accountID) {
        this.realmID = realmID;
        this.shardID = shardID;
        this.accountID = accountID;
    }

    public static AccountVirtualMapKey fromBytes(final Bytes bytes) {
        if (bytes == null) {
            return null;
        }
        final long realmID = bytes.getLong(0);
        final long shardID = bytes.getLong(Long.BYTES);
        final long accountID = bytes.getLong(Long.BYTES * 2);
        return new AccountVirtualMapKey(realmID, shardID, accountID);
    }

    public Bytes toBytes() {
        final byte[] bytes = new byte[Long.BYTES * 3];
        ByteBuffer.wrap(bytes).putLong(realmID).putLong(shardID).putLong(accountID);
        return Bytes.wrap(bytes);
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
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeLong(realmID);
        out.writeLong(shardID);
        out.writeLong(accountID);
    }

    void serialize(final WritableSequentialData out) {
        out.writeLong(realmID);
        out.writeLong(shardID);
        out.writeLong(accountID);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        this.realmID = in.readLong();
        this.shardID = in.readLong();
        this.accountID = in.readLong();
    }

    void deserialize(final ReadableSequentialData in) {
        this.realmID = in.readLong();
        this.shardID = in.readLong();
        this.accountID = in.readLong();
    }

    public boolean equals(final BufferedData buffer) {
        return realmID == buffer.readLong() && shardID == buffer.readLong() && accountID == buffer.readLong();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "AccountVirtualMapKey{" + "realmID="
                + realmID + ", shardID="
                + shardID + ", accountID="
                + accountID + '}';
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        final AccountVirtualMapKey that = (AccountVirtualMapKey) other;
        return realmID == that.realmID && shardID == that.shardID && accountID == that.accountID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(realmID, shardID, accountID);
    }

    public static int getSizeInBytes() {
        return 3 * Long.BYTES;
    }

    /**
     * @return The id of the account that can be found by this key.
     */
    public long getAccountID() {
        return accountID;
    }
}
