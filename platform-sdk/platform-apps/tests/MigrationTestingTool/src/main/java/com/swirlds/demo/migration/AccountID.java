// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * This class represents the key to find an account that is being
 * stored inside a {@link com.swirlds.virtualmap.VirtualMap} instance.
 */
public class AccountID {

    private final long realmID;
    private final long shardID;
    private final long accountID;

    public AccountID() {
        this(0, 0, 0);
    }

    public AccountID(final long realmID, final long shardID, final long accountID) {
        this.realmID = realmID;
        this.shardID = shardID;
        this.accountID = accountID;
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
        final AccountID that = (AccountID) other;
        return realmID == that.realmID && shardID == that.shardID && accountID == that.accountID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(realmID, shardID, accountID);
    }
}
