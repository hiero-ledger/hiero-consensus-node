// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.dummy;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.common.merkle.utility.Keyed;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class Value extends PartialMerkleLeaf implements Keyed<Key>, MerkleLeaf {

    private static final Random RANDOM = new SecureRandom();

    private static final long CLASS_ID = 0x7e28ce72f0d2e239L;

    private Key key;

    private static class ClassVersion {
        /**
         * Versions 1-9 are undocumented.
         */
        public static final int CURRENT = 10;
    }

    private long balance;
    private long sendThresholdValue;
    private long receiveThresholdValue;
    private boolean receiverSignatureRequired;

    public Value() {}

    Value(final ValueBuilder valueBuilder) {
        this.balance = valueBuilder.balance;
        this.sendThresholdValue = valueBuilder.sendThresholdValue;
        this.receiveThresholdValue = valueBuilder.receiveThresholdValue;
        this.receiverSignatureRequired = valueBuilder.receiveSignatureRequired;
    }

    public Value(
            final long balance,
            final long sendThresholdValue,
            final long receiveThresholdValue,
            final boolean receiverSignatureRequired) {
        this.balance = balance;
        this.sendThresholdValue = sendThresholdValue;
        this.receiveThresholdValue = receiveThresholdValue;
        this.receiverSignatureRequired = receiverSignatureRequired;
    }

    private Value(final Value sourceValue) {
        super(sourceValue);
        this.balance = sourceValue.balance;
        this.sendThresholdValue = sourceValue.sendThresholdValue;
        this.receiveThresholdValue = sourceValue.receiveThresholdValue;
        this.receiverSignatureRequired = sourceValue.receiverSignatureRequired;
        if (sourceValue.key != null) {
            this.key = sourceValue.key.copy();
        }
        setImmutable(false);
        sourceValue.setImmutable(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Key getKey() {
        return key;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setKey(final Key key) {
        this.key = key;
    }

    public void setBalance(final long balance) {
        this.balance = balance;
    }

    public void setSendThresholdValue(final long sendThresholdValue) {
        this.sendThresholdValue = sendThresholdValue;
    }

    public void setReceiveThresholdValue(final long receiveThresholdValue) {
        this.receiveThresholdValue = receiveThresholdValue;
    }

    public void setReceiverSignatureRequired(final boolean receiverSignatureRequired) {
        this.receiverSignatureRequired = receiverSignatureRequired;
    }

    public static ValueBuilder newBuilder() {
        return new ValueBuilder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final Value value = (Value) o;
        return balance == value.balance
                && sendThresholdValue == value.sendThresholdValue
                && receiveThresholdValue == value.receiveThresholdValue
                && receiverSignatureRequired == value.receiverSignatureRequired;
    }

    @Override
    public int hashCode() {
        return Objects.hash(balance, sendThresholdValue, receiveThresholdValue, receiverSignatureRequired);
    }

    @Override
    public String toString() {
        return "Value{" + "balance="
                + balance + ", sendThresholdValue="
                + sendThresholdValue + ", receiveThresholdValue="
                + receiveThresholdValue + ", receiverSignatureRequired="
                + receiverSignatureRequired + '}';
    }

    public long getBalance() {
        return this.balance;
    }

    public long getSendThresholdValue() {
        return this.sendThresholdValue;
    }

    public long getReceiveThresholdValue() {
        return this.receiveThresholdValue;
    }

    public boolean isReceiverSignatureRequired() {
        return this.receiverSignatureRequired;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Value copy() {
        throwIfImmutable();
        return new Value(this);
    }

    public static Value buildRandomValue() {
        return ValueBuilder.buildRandomValue();
    }

    @Override
    public int getVersion() {
        return ClassVersion.CURRENT;
    }

    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.CURRENT;
    }

    @Override
    public void serialize(final SerializableDataOutputStream outStream) throws IOException {
        outStream.writeLong(balance);
        outStream.writeLong(sendThresholdValue);
        outStream.writeLong(receiveThresholdValue);
        outStream.writeByte((receiverSignatureRequired ? 1 : 0));
        outStream.writeSerializable(key, false);
    }

    @Override
    public void deserialize(final SerializableDataInputStream inStream, final int version) throws IOException {
        this.balance = inStream.readLong();
        this.sendThresholdValue = inStream.readLong();
        this.receiveThresholdValue = inStream.readLong();
        this.receiverSignatureRequired = inStream.readByte() == 1;
        this.key = inStream.readSerializable(false, Key::new);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    public static class ValueBuilder {

        private long balance;

        private long sendThresholdValue;

        private long receiveThresholdValue;

        private boolean receiveSignatureRequired;

        public static Value buildRandomValue() {
            return new ValueBuilder()
                    .setSendThresholdvalue(RANDOM.nextLong())
                    .setBalance(RANDOM.nextLong())
                    .setReceiveThresholdValue(RANDOM.nextLong())
                    .setReceiveSignatureRequired(RANDOM.nextBoolean())
                    .build();
        }

        ValueBuilder() {}

        public ValueBuilder setBalance(final long balance) {
            this.balance = balance;
            return this;
        }

        public ValueBuilder setSendThresholdvalue(final long sendThresholdValue) {
            this.sendThresholdValue = sendThresholdValue;
            return this;
        }

        public ValueBuilder setReceiveThresholdValue(final long receiveThresholdValue) {
            this.receiveThresholdValue = receiveThresholdValue;
            return this;
        }

        public ValueBuilder setReceiveSignatureRequired(final boolean receiveSignatureRequired) {
            this.receiveSignatureRequired = receiveSignatureRequired;
            return this;
        }

        public Value build() {
            return new Value(this);
        }
    }
}
