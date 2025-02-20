// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.exceptions.IllegalChildIndexException;
import com.swirlds.common.merkle.impl.PartialNaryMerkleInternal;
import com.swirlds.common.merkle.utility.Keyed;
import com.swirlds.fcqueue.FCQueue;
import java.util.Objects;
import java.util.Random;

public class MapValue extends PartialNaryMerkleInternal implements Keyed<AccountID>, MerkleInternal {

    private static final long CLASS_ID = 0x9a048d2898e96abcL;

    private static class ClassVersion {
        public static final int ORIGINAL = 2;
        /**
         * Remove the binary object from this leaf.
         */
        public static final int NO_BLOBS = 3;
    }

    private static class ChildIndices {
        public static final int MAP_KEY = 0;
        public static final int RECORDS = 1;
        public static final int INTERNAL_VALUE = 2;

        public static final int CHILD_COUNT = 3;
    }

    public MapValue() {
        setMapKey(new MapKey());
        setRecords(new FCQueue<>());
        setInternalValue(new InternalValue());
    }

    private MapValue(
            final AccountID key,
            final long balance,
            final long expirationTime,
            final long autoRenewPeriod,
            final long senderThreshold,
            final long receiverThreshold,
            final String memo,
            final boolean isSmartContract,
            final boolean receiverSigRequired,
            final FCQueue<TransactionRecord> records) {
        super();
        setMapKey(new MapKey(key));
        setInternalValue(new InternalValue(
                balance,
                expirationTime,
                autoRenewPeriod,
                senderThreshold,
                receiverThreshold,
                memo,
                isSmartContract,
                receiverSigRequired));
        setRecords(records);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfChildren() {
        return ChildIndices.CHILD_COUNT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumChildCount() {
        return ChildIndices.CHILD_COUNT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMaximumChildCount() {
        return ChildIndices.CHILD_COUNT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean childHasExpectedType(int index, long childClassId) {
        switch (index) {
            case ChildIndices.MAP_KEY:
                return childClassId == MapKey.CLASS_ID;
            case ChildIndices.RECORDS:
                return childClassId == FCQueue.CLASS_ID;
            case ChildIndices.INTERNAL_VALUE:
                return childClassId == InternalValue.CLASS_ID;
            default:
                throw new IllegalChildIndexException(getMinimumChildCount(), getMaximumChildCount(), index);
        }
    }

    private MapKey getMapKey() {
        return getChild(ChildIndices.MAP_KEY);
    }

    private void setMapKey(MapKey mapKey) {
        setChild(ChildIndices.MAP_KEY, mapKey);
    }

    private InternalValue getInternalValue() {
        return getChild(ChildIndices.INTERNAL_VALUE);
    }

    private void setInternalValue(InternalValue internalValue) {
        setChild(ChildIndices.INTERNAL_VALUE, internalValue);
    }

    private FCQueue<TransactionRecord> getRecords() {
        return getChild(ChildIndices.RECORDS);
    }

    private void setRecords(FCQueue<TransactionRecord> records) {
        setChild(ChildIndices.RECORDS, records);
    }

    private MapValue(final MapValue mapValue) {
        super();
        if (mapValue.getMapKey() != null) {
            setMapKey(mapValue.getMapKey().copy());
        }
        if (mapValue.getInternalValue() != null) {
            setInternalValue(mapValue.getInternalValue().copy());
        }
        if (mapValue.getRecords() != null) {
            setRecords(mapValue.getRecords().copy());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MapValue copy() {
        throwIfImmutable();
        return new MapValue(this);
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
        final MapValue that = (MapValue) other;
        return Objects.equals(this.getMapKey(), that.getMapKey())
                && Objects.equals(this.getInternalValue(), that.getInternalValue())
                && Objects.equals(this.getRecords(), that.getRecords());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.getMapKey(), this.getInternalValue(), this.getRecords());
    }

    static MapValue generateRandom(final Random random, final AccountID key) {
        final long balance = random.nextLong();
        final long expirationtime = random.nextLong();
        final long autoRenewPeriod = random.nextLong();
        final long senderThreshold = random.nextLong();
        final long receiverThreshold = random.nextLong();
        final boolean isSmartContract = random.nextBoolean();
        final boolean receiverSigRequired = random.nextBoolean();
        final byte[] string = new byte[50];
        random.nextBytes(string);
        final String memo = new String(string);
        final byte[] internalData = new byte[100];
        random.nextBytes(internalData);
        final FCQueue<TransactionRecord> records = generateRandomFCQ(random);
        return new MapValue(
                key,
                balance,
                expirationtime,
                autoRenewPeriod,
                senderThreshold,
                receiverThreshold,
                memo,
                isSmartContract,
                receiverSigRequired,
                records);
    }

    public static FCQueue<TransactionRecord> generateRandomFCQ(final Random random) {
        final FCQueue<TransactionRecord> records = new FCQueue<>();
        for (int index = 0; index < 10; index++) {
            records.add(TransactionRecord.generateRandom(random));
        }

        return records;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AccountID getKey() {
        return ((MapKey) getChild(ChildIndices.MAP_KEY)).getAccountId();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setKey(final AccountID accountID) {
        if (getChild(ChildIndices.MAP_KEY) == null) {
            setChild(ChildIndices.MAP_KEY, new MapKey(accountID));
        } else {
            getMapKey().setAccountId(accountID);
        }
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
        return ClassVersion.NO_BLOBS;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.NO_BLOBS;
    }
}
