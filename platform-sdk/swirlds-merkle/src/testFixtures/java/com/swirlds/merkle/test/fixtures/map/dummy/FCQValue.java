// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.dummy;

import com.swirlds.common.FastCopyable;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.exceptions.IllegalChildIndexException;
import com.swirlds.common.merkle.impl.PartialNaryMerkleInternal;
import com.swirlds.common.merkle.utility.Keyed;
import com.swirlds.common.merkle.utility.MerkleLong;
import com.swirlds.common.test.fixtures.dummy.Key;
import com.swirlds.common.test.fixtures.dummy.MerkleKey;
import com.swirlds.fcqueue.FCQueue;
import com.swirlds.merkle.test.fixtures.map.pta.TransactionRecord;
import com.swirlds.merkle.test.fixtures.map.util.MerkleMapTestUtil;
import java.util.Iterator;
import java.util.Objects;
import org.hiero.base.crypto.SerializableHashable;

/**
 * MapValue with a {@link FCQueue}
 */
public class FCQValue<T extends FastCopyable & SerializableHashable> extends PartialNaryMerkleInternal
        implements Iterable<T>, Keyed<Key>, MerkleInternal {

    protected static final int FCQ_VALUE_TYPE = 5;

    private static final long CLASS_ID = 0x9ce2813e6b425a9cL;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private static class ChildIndices {
        public static final int BALANCE = 0;
        public static final int RECORDS = 1;
        public static final int KEY = 2;

        public static final int CHILD_COUNT = 2;
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
                return childClassId == MerkleKey.CLASS_ID;
            default:
                throw new IllegalChildIndexException(getMinimumChildCount(), getMaximumChildCount(), index);
        }
    }

    private MerkleLong getBalance() {
        return getChild(ChildIndices.BALANCE);
    }

    private void setBalance(MerkleLong balance) {
        setChild(ChildIndices.BALANCE, balance);
    }

    public FCQueue<T> getRecords() {
        return getChild(ChildIndices.RECORDS);
    }

    private void setRecords(FCQueue<T> records) {
        setChild(ChildIndices.RECORDS, records);
    }

    public static FCQValue<TransactionRecord> build(final long index, final long balance, final byte[] content) {
        final TransactionRecord record =
                new TransactionRecord(index, balance, content, TransactionRecord.DEFAULT_EXPIRATION_TIME);
        final FCQueue<TransactionRecord> records = new FCQueue<>();
        records.add(record);
        return new FCQValue<>(new MerkleLong(record.getBalance()), records);
    }

    public FCQValue() {
        super();
        setBalance(new MerkleLong());
        setRecords(new FCQueue<>());
    }

    public FCQValue(final MerkleLong balance, final FCQueue<T> records) {
        super();
        setBalance(balance);
        setRecords(records);
    }

    private FCQValue(final FCQValue<T> sourceValue) {
        super(sourceValue);
        setBalance(sourceValue.getBalance());
        setRecords(sourceValue.getRecords().copy());
        setKey(sourceValue.getKey().copy());
        setImmutable(false);
        sourceValue.setImmutable(true);
    }

    public int getFCQRecordsSize() {
        return getRecords().size();
    }

    public FCQValue<T> deleteFirst() {
        final FCQueue<T> newRecords = getRecords().copy();
        newRecords.poll();
        return new FCQValue<>(getBalance().copy(), newRecords);
    }

    @SuppressWarnings("unchecked")
    public FCQValue<T> transferFrom(final long balance, final byte[] content) {
        final MerkleLong newBalance = new MerkleLong(getBalance().getValue() - balance);
        final T newFromTransactionRecord = (T)
                new TransactionRecord(getRecords().size(), balance, content, TransactionRecord.DEFAULT_EXPIRATION_TIME);
        return this.addRecord(newBalance, newFromTransactionRecord);
    }

    public static FCQValue<TransactionRecord> buildDefault() {
        final byte[] content = new byte[4_096];
        MerkleMapTestUtil.random.nextBytes(content);
        final int index = 0;
        final long balance = 100;
        return build(index, balance, content);
    }

    public static FCQValue<TransactionRecord> buildRandom() {
        final byte[] content = new byte[100];
        MerkleMapTestUtil.random.nextBytes(content);
        final int index = MerkleMapTestUtil.random.nextInt();
        final long balance = MerkleMapTestUtil.random.nextLong();
        return build(index, balance, content);
    }

    public static FCQValue<TransactionRecord> buildRandomWithIndex(final int index) {
        final byte[] content = new byte[100];
        MerkleMapTestUtil.random.nextBytes(content);
        final long balance = MerkleMapTestUtil.random.nextLong();
        return build(index, balance, content);
    }

    @SuppressWarnings("unchecked")
    public FCQValue<T> transferTo(final long balance, final byte[] content) {
        final MerkleLong newBalance = new MerkleLong(getBalance().getValue() + balance);
        final T newFromTransactionRecord = (T)
                new TransactionRecord(getRecords().size(), balance, content, TransactionRecord.DEFAULT_EXPIRATION_TIME);
        return this.addRecord(newBalance, newFromTransactionRecord);
    }

    public FCQValue<T> addRecord(final long newBalance, final T record) {
        return this.addRecord(new MerkleLong(newBalance), record);
    }

    public FCQValue<T> addRecord(final MerkleLong newBalance, final T record) {
        final FCQueue<T> newRecords = getRecords().copy();
        newRecords.add(record);
        return new FCQValue<>(newBalance, newRecords);
    }

    public void addRecord(final T record) {
        getRecords().add(record);
    }

    @Override
    public FCQValue<T> copy() {
        throwIfImmutable();
        return new FCQValue<>(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof FCQValue)) {
            return false;
        }

        final FCQValue<?> that = (FCQValue<?>) o;
        return getBalance().equals(that.getBalance()) && Objects.equals(getRecords(), that.getRecords());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getBalance(), getRecords());
    }

    @Override
    public Iterator<T> iterator() {
        return getRecords().iterator();
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
    public Key getKey() {
        return ((MerkleKey) getChild(ChildIndices.KEY)).getKey();
    }

    @Override
    public void setKey(final Key key) {
        setChild(ChildIndices.KEY, new MerkleKey(key));
    }
}
