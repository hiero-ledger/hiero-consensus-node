// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.merkle.map;

import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyProvider;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.common.merkle.utility.Keyed;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.merkle.test.fixtures.map.pta.MapValue;
import java.io.IOException;
import java.io.Serializable;
import java.util.Objects;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class MapValueData extends PartialMerkleLeaf implements Keyed<MapKey>, MapValue, Serializable, MerkleLeaf {
    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();

    public static final long CLASS_ID = 0x206bc63a03b16c28L;

    private MapKey key;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
        public static final int UID = 2;
    }

    private long balance;
    private long sendThresholdValue;
    private long receiveThresholdValue;
    private boolean receiverSignatureRequired;
    private long uid;

    public MapValueData() {}

    public MapValueData(
            final long balance,
            final long sendThresholdValue,
            final long receiveThresholdValue,
            final boolean isReceiverSignatureRequired,
            final long uid) {
        this.balance = balance;
        this.sendThresholdValue = sendThresholdValue;
        this.receiveThresholdValue = receiveThresholdValue;
        this.receiverSignatureRequired = isReceiverSignatureRequired;
        this.uid = uid;
    }

    private MapValueData(final MapValueData sourceValue) {
        super(sourceValue);
        this.balance = sourceValue.getBalance();
        this.sendThresholdValue = sourceValue.getSendThresholdValue();
        this.receiveThresholdValue = sourceValue.getReceiveThresholdValue();
        this.receiverSignatureRequired = sourceValue.isReceiverSignatureRequired();
        this.uid = sourceValue.getUid();
        setKey(sourceValue.getKey().copy());
        setImmutable(false);
        sourceValue.setImmutable(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MapKey getKey() {
        return key;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setKey(final MapKey key) {
        this.key = key;
    }

    public long getBalance() {
        return this.balance;
    }

    public void setBalance(long balance) {
        invalidateHash();
        this.balance = balance;
    }

    public long getSendThresholdValue() {
        return this.sendThresholdValue;
    }

    public void setSendThresholdValue(long sendThresholdValue) {
        invalidateHash();
        this.sendThresholdValue = sendThresholdValue;
    }

    public long getReceiveThresholdValue() {
        return this.receiveThresholdValue;
    }

    public void setReceiveThresholdValue(long receiveThreshold) {
        invalidateHash();
        this.receiveThresholdValue = receiveThreshold;
    }

    public boolean isReceiverSignatureRequired() {
        return this.receiverSignatureRequired;
    }

    public void setReceiverSignatureRequired(boolean required) {
        invalidateHash();
        this.receiverSignatureRequired = required;
    }

    @Override
    public Hash calculateHash() {
        if (getHash() != null) {
            return getHash();
        }

        CRYPTOGRAPHY.digestSync(this);
        return getHash();
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        final MapValueData that = (MapValueData) other;
        return balance == that.balance
                && sendThresholdValue == that.sendThresholdValue
                && receiveThresholdValue == that.receiveThresholdValue
                && receiverSignatureRequired == that.receiverSignatureRequired
                && uid == that.uid;
    }

    @Override
    public int hashCode() {
        return Objects.hash(balance, sendThresholdValue, receiveThresholdValue, receiverSignatureRequired, uid);
    }

    public String toString() {
        return "MapValue{" + "balance="
                + balance + ", sendThresholdValue="
                + sendThresholdValue + ", receiveThresholdValue="
                + receiveThresholdValue + ", receiverSignatureRequired="
                + receiverSignatureRequired + '}';
    }

    @Override
    public MapValueData copy() {
        throwIfImmutable();
        return new MapValueData(this);
    }

    @Override
    public void serialize(SerializableDataOutputStream outStream) throws IOException {
        outStream.writeLong(this.balance);
        outStream.writeLong(this.sendThresholdValue);
        outStream.writeLong(this.receiveThresholdValue);
        outStream.writeBoolean(this.receiverSignatureRequired);
        outStream.writeLong(this.uid);
        outStream.writeSerializable(key, false);
    }

    @Override
    public void deserialize(SerializableDataInputStream inStream, int version) throws IOException {
        this.balance = inStream.readLong();
        this.sendThresholdValue = inStream.readLong();
        this.receiveThresholdValue = inStream.readLong();
        this.receiverSignatureRequired = inStream.readBoolean();
        if (version == ClassVersion.UID) {
            this.uid = inStream.readLong();
        }
        key = inStream.readSerializable(false, MapKey::new);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return ClassVersion.UID;
    }

    @Override
    public long getUid() {
        return uid;
    }
}
