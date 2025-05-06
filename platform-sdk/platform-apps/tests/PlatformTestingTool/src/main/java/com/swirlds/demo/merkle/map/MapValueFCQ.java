// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.merkle.map;

import com.swirlds.common.FastCopyable;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.exceptions.IllegalChildIndexException;
import com.swirlds.common.merkle.impl.PartialNaryMerkleInternal;
import com.swirlds.common.merkle.utility.Keyed;
import com.swirlds.common.merkle.utility.MerkleLong;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.demo.platform.expiration.ExpirationRecordEntry;
import com.swirlds.demo.platform.expiration.ExpirationUtils;
import com.swirlds.fcqueue.FCQueue;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.merkle.test.fixtures.map.pta.MapValue;
import com.swirlds.merkle.test.fixtures.map.pta.MerkleMapKey;
import com.swirlds.merkle.test.fixtures.map.pta.TransactionRecord;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.SerializableHashable;

/**
 * MapValue with a {@link FCQueue}
 */
public class MapValueFCQ<T extends FastCopyable & SerializableHashable> extends PartialNaryMerkleInternal
        implements Keyed<MapKey>, MapValue, MerkleInternal {

    private static final long CLASS_ID = 0xed49aa414bec6dbdL;

    public MapValueFCQ(final long balance, final FCQueue<T> records) {
        super();
        setBalance(new MerkleLong(balance));
        setRecords(records);
    }

    private MapValueFCQ(final MapValueFCQ<T> sourceValue) {
        setBalance(new MerkleLong(sourceValue.getBalanceValue()));
        setRecords(sourceValue.getRecords().copy());
        setKey(sourceValue.getKey());
        setImmutable(false);
        sourceValue.setImmutable(true);
    }

    public MapValueFCQ() {
        super();
        setRecords(new FCQueue<>());
        setBalance(new MerkleLong());
    }

    public static MapValueFCQBuilder newBuilder() {
        return new MapValueFCQBuilder();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean childHasExpectedType(int index, long childClassId) {
        switch (index) {
            case ChildIndices.BALANCE:
                return childClassId == MerkleLong.CLASS_ID;
            case ChildIndices.RECORDS:
                return childClassId == FCQueue.CLASS_ID;
            case ChildIndices.KEY:
                return childClassId == MerkleMapKey.CLASS_ID;
            default:
                throw new IllegalChildIndexException(getMinimumChildCount(), getMaximumChildCount(), index);
        }
    }

    public MerkleLong getBalance() {
        return getChild(ChildIndices.BALANCE);
    }

    /**
     * Set or replace the balance on a leaf
     *
     * @param balance
     */
    public void setBalance(MerkleLong balance) {
        setChild(ChildIndices.BALANCE, balance);
    }

    public FCQueue<T> getRecords() {
        return getChild(ChildIndices.RECORDS);
    }

    /**
     * Set or replace the record list on a leaf
     *
     * @param records
     */
    public void setRecords(FCQueue<T> records) {
        setChild(ChildIndices.RECORDS, records);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MapKey getKey() {
        final MerkleMapKey merkleMapKey = getChild(ChildIndices.KEY);
        if (merkleMapKey == null) {
            return null;
        }

        return merkleMapKey.getMapKey();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setKey(final MapKey key) {
        setChild(ChildIndices.KEY, new MerkleMapKey(key));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash calculateHash() {
        if (getHash() != null) {
            return getHash();
        }

        return TestMerkleCryptoFactory.getInstance().digestTreeSync(this);
    }

    public long getBalanceValue() {
        return getBalance().getValue();
    }

    public int getRecordsSize() {
        return this.getRecords().size();
    }

    public MapValueFCQ<T> deleteFirst() {
        final FCQueue<T> newRecords = getRecords();
        newRecords.poll();
        return this;
    }

    public MapValueFCQ<T> transferFrom(
            final long balance,
            final byte[] content,
            final MapKey mapKey,
            final long expirationTime,
            final BlockingQueue<ExpirationRecordEntry> expirationQueue,
            final Set<MapKey> accountsWithExpiringRecords) {
        final long newBalance = this.getBalanceValue() - balance;

        @SuppressWarnings("unchecked")
        final T newFromTransactionRecord =
                (T) new TransactionRecord(getRecords().size(), balance, content, expirationTime);

        return this.addRecord(
                newBalance, newFromTransactionRecord, mapKey, expirationQueue, accountsWithExpiringRecords);
    }

    public MapValueFCQ<T> transferTo(
            final long balance,
            final byte[] content,
            final MapKey mapKey,
            final long expirationTime,
            final BlockingQueue<ExpirationRecordEntry> expirationQueue,
            final Set<MapKey> accountsWithExpiringRecords) {
        final long newBalance = this.getBalance().getValue() + balance;

        @SuppressWarnings("unchecked")
        final T newFromTransactionRecord =
                (T) new TransactionRecord(getRecords().size(), balance, content, expirationTime);

        return this.addRecord(
                newBalance, newFromTransactionRecord, mapKey, expirationQueue, accountsWithExpiringRecords);
    }

    public MapValueFCQ<T> addRecord(
            final long newBalance,
            final T record,
            final MapKey mapKey,
            final BlockingQueue<ExpirationRecordEntry> expirationQueue,
            final Set<MapKey> accountsWithExpiringRecords) {
        final FCQueue<T> newRecords = getRecords();
        newRecords.add(record);
        setBalance(new MerkleLong(newBalance));
        ExpirationUtils.addRecordToExpirationQueue(
                (TransactionRecord) record, mapKey, expirationQueue, accountsWithExpiringRecords);
        return this;
    }

    public MapValueFCQ<T> addRecords(
            final long newBalance,
            final Collection<T> records,
            final MapKey mapKey,
            final BlockingQueue<ExpirationRecordEntry> expirationQueue,
            final Set<MapKey> accountsWithExpiringRecords) {

        getRecords().addAll(records);
        for (T record : records) {
            ExpirationUtils.addRecordToExpirationQueue(
                    (TransactionRecord) record, mapKey, expirationQueue, accountsWithExpiringRecords);
        }
        setBalance(new MerkleLong(newBalance));
        return this;
    }

    @Override
    public MapValueFCQ<T> copy() {
        throwIfImmutable();
        return new MapValueFCQ<>(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MapValueFCQ<?> that = (MapValueFCQ<?>) o;
        return getBalance() == that.getBalance() && getRecords().equals(that.getRecords());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getBalance(), getRecords());
    }

    @Override
    public String toString() {
        return "MapValueFCQ{" + "balance=" + getBalance() + ", records=" + getRecords() + '}';
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private static class ChildIndices {
        public static final int BALANCE = 0;
        public static final int RECORDS = 1;
        public static final int KEY = 2;

        public static final int CHILD_COUNT = 3;
    }

    public static class MapValueFCQBuilder<T extends MapValueFCQ.MapValueFCQBuilder<T>> {
        private long index;
        private long balance;
        private byte[] content;
        private MapKey mapKey;
        private long expirationTime;
        private BlockingQueue<ExpirationRecordEntry> expirationQueue;
        private Set<MapKey> accountsWithExpiringRecords;

        MapValueFCQBuilder() {}

        public MapValueFCQBuilder setBalance(long balance) {
            this.balance = balance;
            return this;
        }

        public MapValueFCQBuilder setIndex(long index) {
            this.index = index;
            return this;
        }

        public MapValueFCQBuilder setContent(byte[] content) {
            this.content = content;
            return this;
        }

        public MapValueFCQBuilder setMapKey(MapKey mapKey) {
            this.mapKey = mapKey;
            return this;
        }

        public MapValueFCQBuilder setExpirationTime(long expirationTime) {
            this.expirationTime = expirationTime;
            return this;
        }

        public MapValueFCQBuilder setExpirationQueue(BlockingQueue<ExpirationRecordEntry> expirationQueue) {
            this.expirationQueue = expirationQueue;
            return this;
        }

        public MapValueFCQBuilder setAccountsWithExpiringRecords(Set<MapKey> accountsWithExpiringRecords) {
            this.accountsWithExpiringRecords = accountsWithExpiringRecords;
            return this;
        }

        public MapValueFCQ<TransactionRecord> build() {
            final TransactionRecord record = new TransactionRecord(index, balance, content, expirationTime);
            final FCQueue<TransactionRecord> records = new FCQueue<>();
            records.add(record);
            ExpirationUtils.addRecordToExpirationQueue(record, mapKey, expirationQueue, accountsWithExpiringRecords);
            return new MapValueFCQ<>(record.getBalance(), records);
        }
    }
}
