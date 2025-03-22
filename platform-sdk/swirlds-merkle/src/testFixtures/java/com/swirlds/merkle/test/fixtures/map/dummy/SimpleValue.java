// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.dummy;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class SimpleValue extends PartialMerkleLeaf implements MerkleLeaf {

    private static final long CLASS_ID = 0x9475f1a4061a532aL;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    protected static final int EXTENDED_VALUE_TYPE = 501;

    protected static final int VALUE_VERSION = 10;

    private long balance;
    private long sendThresholdValue;
    private long receiveThresholdValue;

    public SimpleValue() {}

    SimpleValue(final ValueBuilder valueBuilder) {
        this.balance = valueBuilder.balance;
        this.sendThresholdValue = valueBuilder.sendThresholdValue;
        this.receiveThresholdValue = valueBuilder.receiveThresholdValue;
    }

    SimpleValue(final long balance, final long sendThresholdValue, final long receiveThresholdValue) {
        this.balance = balance;
        this.sendThresholdValue = sendThresholdValue;
        this.receiveThresholdValue = receiveThresholdValue;
    }

    private SimpleValue(final SimpleValue sourceValue) {
        super(sourceValue);
        this.balance = sourceValue.getBalance();
        this.sendThresholdValue = sourceValue.getSendThresholdValue();
        this.receiveThresholdValue = sourceValue.getReceiveThresholdValue();
        setImmutable(false);
        sourceValue.setImmutable(true);
    }

    public static SimpleValue deserialize(final DataInputStream inputStream) throws IOException {
        final int valueVersion = inputStream.readInt();
        if (valueVersion != VALUE_VERSION) {
            throw new IOException(String.format(
                    "Invalid value version. Expected %d. Received %d", EXTENDED_VALUE_TYPE, valueVersion));
        }

        inputStream.readInt();
        return deserializeValue(inputStream);
    }

    protected static SimpleValue deserializeValue(final DataInputStream inputStream) throws IOException {
        return SimpleValue.newBuilder()
                .setBalance(inputStream.readLong())
                .setSendThresholdvalue(inputStream.readLong())
                .setReceiveThresholdValue(inputStream.readLong())
                .setReceiveSignatureRequired(inputStream.readByte() == 1)
                .build();
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

        SimpleValue value = (SimpleValue) o;
        return balance == value.balance
                && sendThresholdValue == value.sendThresholdValue
                && receiveThresholdValue == value.receiveThresholdValue;
    }

    @Override
    public int hashCode() {
        return Objects.hash(balance, sendThresholdValue, receiveThresholdValue);
    }

    @Override
    public String toString() {
        return "Value{" + "balance="
                + balance + ", sendThresholdValue="
                + sendThresholdValue + ", receiveThresholdValue="
                + receiveThresholdValue + '}';
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(SerializableDataOutputStream outStream) throws IOException {
        outStream.writeLong(this.balance);
        outStream.writeLong(this.sendThresholdValue);
        outStream.writeLong(this.receiveThresholdValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(SerializableDataInputStream inputStream, int version) throws IOException {
        this.balance = inputStream.readLong();
        this.sendThresholdValue = inputStream.readLong();
        this.receiveThresholdValue = inputStream.readLong();
    }

    public static class ValueBuilder<T extends ValueBuilder<T>> {

        private long balance;

        private long sendThresholdValue;

        private long receiveThresholdValue;

        private boolean receiveSignatureRequired;

        ValueBuilder() {}

        public T setBalance(final long balance) {
            this.balance = balance;
            return getThis();
        }

        public T setSendThresholdvalue(final long sendThresholdValue) {
            this.sendThresholdValue = sendThresholdValue;
            return getThis();
        }

        public T setReceiveThresholdValue(final long receiveThresholdValue) {
            this.receiveThresholdValue = receiveThresholdValue;
            return getThis();
        }

        public T setReceiveSignatureRequired(final boolean receiveSignatureRequired) {
            this.receiveSignatureRequired = receiveSignatureRequired;
            return getThis();
        }

        public SimpleValue build() {
            return new SimpleValue(this);
        }

        protected T getThis() {
            return (T) this;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SimpleValue copy() {
        throwIfImmutable();
        return new SimpleValue(this);
    }
}
