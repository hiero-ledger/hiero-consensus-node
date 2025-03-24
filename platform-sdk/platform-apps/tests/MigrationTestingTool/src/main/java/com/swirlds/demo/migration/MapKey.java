// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public final class MapKey extends PartialMerkleLeaf implements MerkleLeaf {

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    public static final long CLASS_ID = 0x35c5acefca4a4fd4L;

    private AccountID accountId;

    public MapKey() {
        this.accountId = new AccountID();
    }

    public MapKey(final AccountID accountId) {
        this.accountId = accountId;
    }

    private MapKey(final MapKey mapKey) {
        super(mapKey);
        this.accountId = mapKey.accountId.copy();
        setImmutable(false);
        mapKey.setImmutable(true);
    }

    /**
     * Get the account ID.
     */
    public AccountID getAccountId() {
        return accountId;
    }

    /**
     * Set the account ID.
     */
    public void setAccountId(final AccountID accountId) {
        this.accountId = accountId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MapKey copy() {
        throwIfImmutable();
        return new MapKey(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof MapKey)) {
            return false;
        }

        final MapKey mapKey = (MapKey) o;
        return accountId.equals(mapKey.accountId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(accountId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return this.accountId.toString();
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

    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeSerializable(this.accountId, true);
    }

    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        this.accountId = in.readSerializable();
    }
}
