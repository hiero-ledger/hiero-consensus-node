// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.FastCopyable;
import java.io.IOException;
import java.util.Objects;
import java.util.Random;
import org.hiero.base.crypto.AbstractSerializableHashable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

public class TransactionRecord extends AbstractSerializableHashable implements FastCopyable {

    private static final long CLASS_ID = 0x8aedf9f191bd5448L;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
        public static final int MIGRATE_TO_SERIALIZABLE = 2;
    }

    private String status;
    private AccountID accountID;
    private AccountID fileID;
    private AccountID contractID;
    private FCTimestamp timestamp;
    private TransactionHash transactionHash;
    private boolean deleted;

    private boolean immutable;

    private TransactionRecord(
            final String status,
            final AccountID accountID,
            final AccountID fileID,
            final AccountID contractID,
            final FCTimestamp timestamp,
            final TransactionHash transactionHash) {
        this.status = status;
        this.accountID = accountID;
        this.fileID = fileID;
        this.contractID = contractID;
        this.timestamp = timestamp;
        this.transactionHash = transactionHash;
    }

    private TransactionRecord(final TransactionRecord transactionRecord) {
        this.status = transactionRecord.status;
        this.accountID = transactionRecord.accountID.copy();
        this.fileID = transactionRecord.fileID.copy();
        this.contractID = transactionRecord.contractID.copy();
        this.timestamp = transactionRecord.timestamp.copy();
        this.transactionHash = transactionRecord.transactionHash.copy();
        this.immutable = false;
        transactionRecord.immutable = true;
    }

    public TransactionRecord() {
        this.accountID = new AccountID();
        this.fileID = new AccountID();
        this.contractID = new AccountID();
        this.timestamp = new FCTimestamp();
        this.transactionHash = new TransactionHash();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TransactionRecord copy() {
        throwIfImmutable();
        return new TransactionRecord(this);
    }

    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        if (this.deleted) {
            throw new IllegalStateException("Trying to serialize a deleted TransactionRecord");
        }
        out.writeNormalisedString(this.status);
        out.writeSerializable(this.accountID, false);
        out.writeSerializable(this.fileID, false);
        out.writeSerializable(this.contractID, false);
        out.writeSerializable(this.timestamp, false);
        out.writeSerializable(this.transactionHash, false);
    }

    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        this.status = in.readNormalisedString(Integer.MAX_VALUE);
        this.accountID = in.readSerializable(false, AccountID::new);
        this.fileID = in.readSerializable(false, AccountID::new);
        this.contractID = in.readSerializable(false, AccountID::new);
        this.timestamp = in.readSerializable(false, FCTimestamp::new);
        this.transactionHash = in.readSerializable(false, TransactionHash::new);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean release() {
        this.deleted = true;
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof TransactionRecord)) {
            return false;
        }

        final TransactionRecord that = (TransactionRecord) o;
        return status.equals(that.status)
                && accountID.equals(that.accountID)
                && fileID.equals(that.fileID)
                && contractID.equals(that.contractID)
                && timestamp.equals(that.timestamp)
                && transactionHash.equals(that.transactionHash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(status, accountID, fileID, contractID, timestamp, transactionHash);
    }

    static TransactionRecord generateRandom(final Random random) {
        final byte[] data = new byte[50];
        random.nextBytes(data);
        final String status = new String(data);
        final AccountID accountID = AccountID.generateRandom(random);
        final AccountID fileID = AccountID.generateRandom(random);
        final AccountID contractID = AccountID.generateRandom(random);
        final FCTimestamp timestamp = FCTimestamp.generateRandom(random);
        final TransactionHash transactionHash = TransactionHash.generateRandom(random);
        return new TransactionRecord(status, accountID, fileID, contractID, timestamp, transactionHash);
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
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("status", status.getBytes())
                .append("accountID", accountID)
                .append("fileID", fileID)
                .append("contractID", contractID)
                .append("timestamp", timestamp)
                .append("transactionHash", transactionHash)
                .append("deleted", deleted)
                .toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isImmutable() {
        return this.immutable;
    }
}
