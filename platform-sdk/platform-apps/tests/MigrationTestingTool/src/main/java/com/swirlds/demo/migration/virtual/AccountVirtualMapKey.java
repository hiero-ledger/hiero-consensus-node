// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration.virtual;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.VirtualKey;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

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

    @Deprecated
    void serialize(final ByteBuffer buffer) {
        buffer.putLong(realmID);
        buffer.putLong(shardID);
        buffer.putLong(accountID);
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

    @Deprecated
    void deserialize(final ByteBuffer buffer) {
        this.realmID = buffer.getLong();
        this.shardID = buffer.getLong();
        this.accountID = buffer.getLong();
    }

    boolean equals(final BufferedData buffer) {
        return realmID == buffer.readLong() && shardID == buffer.readLong() && accountID == buffer.readLong();
    }

    @Deprecated
    boolean equals(final ByteBuffer buffer, final int version) {
        return realmID == buffer.getLong() && shardID == buffer.getLong() && accountID == buffer.getLong();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "AccountVirtualMapKey{" + "realmID="
                + realmID + ", shardId="
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
}
