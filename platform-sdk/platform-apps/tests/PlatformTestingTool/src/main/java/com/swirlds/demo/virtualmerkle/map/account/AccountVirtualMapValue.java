// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.map.account;

import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.VirtualValue;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * This class represents an account being store inside
 * a {@link com.swirlds.virtualmap.VirtualMap} instance.
 */
public class AccountVirtualMapValue implements VirtualValue {
    private static final long CLASS_ID = 0xd68a5aec20392ff5L;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private long balance;
    private long sendThreshold;
    private long receiveThreshold;
    private boolean requireSignature;
    private long uid;

    public AccountVirtualMapValue() {
        this(0, 0, 0, false, 0);
    }

    public AccountVirtualMapValue(
            final long balance,
            final long sendThreshold,
            final long receiveThreshold,
            final boolean requireSignature,
            final long uid) {
        this.balance = balance;
        this.sendThreshold = sendThreshold;
        this.receiveThreshold = receiveThreshold;
        this.requireSignature = requireSignature;
        this.uid = uid;
    }

    public AccountVirtualMapValue(final AccountVirtualMapValue accountVirtualMapValue) {
        this.balance = accountVirtualMapValue.balance;
        this.sendThreshold = accountVirtualMapValue.sendThreshold;
        this.receiveThreshold = accountVirtualMapValue.receiveThreshold;
        this.requireSignature = accountVirtualMapValue.requireSignature;
        this.uid = accountVirtualMapValue.uid;
    }

    public AccountVirtualMapValue(final ReadableSequentialData in) throws ParseException {
        if (in.remaining() < Long.BYTES * 4 + 1) {
            throw new ParseException("Not enough bytes in input");
        }
        this.balance = in.readLong();
        this.sendThreshold = in.readLong();
        this.receiveThreshold = in.readLong();
        this.requireSignature = in.readByte() != 0;
        this.uid = in.readLong();
    }

    public static AccountVirtualMapValue fromBytes(final Bytes bytes) {
        if (bytes == null) {
            return null;
        }
        return new AccountVirtualMapValue(
                bytes.getLong(0),
                bytes.getLong(Long.BYTES),
                bytes.getLong(Long.BYTES * 2),
                bytes.getByte(Long.BYTES * 3) != 0,
                bytes.getLong(Long.BYTES * 3 + 1));
    }

    public Bytes toBytes() {
        final byte[] bytes = new byte[Long.BYTES + 1];
        ByteBuffer.wrap(bytes)
                .putLong(balance)
                .putLong(sendThreshold)
                .putLong(receiveThreshold)
                .put(getRequireSignatureAsByte())
                .putLong(uid);
        return Bytes.wrap(bytes);
    }

    /**
     * @return The total size in bytes of all the fields of this class.
     */
    public int getSizeInBytes() {
        return Long.BYTES * 4 + 1;
    }

    public void writeTo(final WritableSequentialData out) {
        out.writeLong(balance);
        out.writeLong(sendThreshold);
        out.writeLong(receiveThreshold);
        out.writeByte(getRequireSignatureAsByte());
        out.writeLong(uid);
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
        out.writeLong(balance);
        out.writeLong(sendThreshold);
        out.writeLong(receiveThreshold);
        out.write(getRequireSignatureAsByte());
        out.writeLong(uid);
    }

    void serialize(final WritableSequentialData out) {
        out.writeLong(balance);
        out.writeLong(sendThreshold);
        out.writeLong(receiveThreshold);
        out.writeByte(getRequireSignatureAsByte());
        out.writeLong(uid);
    }

    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        this.balance = in.readLong();
        this.sendThreshold = in.readLong();
        this.receiveThreshold = in.readLong();
        this.requireSignature = in.readByte() == 1;
        this.uid = in.readLong();
    }

    void deserialize(final ReadableSequentialData in) {
        this.balance = in.readLong();
        this.sendThreshold = in.readLong();
        this.receiveThreshold = in.readLong();
        this.requireSignature = in.readByte() == 1;
        this.uid = in.readLong();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualValue copy() {
        return new AccountVirtualMapValue(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualValue asReadOnly() {
        return new AccountVirtualMapValue(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "AccountVirtualMapValue{" + "balance="
                + balance + ", sendThreshold="
                + sendThreshold + ", receiveThreshold="
                + receiveThreshold + ", requireSignature="
                + requireSignature + ", uid="
                + uid + '}';
    }

    /**
     * @return Return {@code 1} if {@code requireSignature} is true, {@code 0} otherwise.
     */
    private byte getRequireSignatureAsByte() {
        return (byte) (requireSignature ? 1 : 0);
    }

    /**
     * @return Return the balance of the account.
     */
    public long getBalance() {
        return balance;
    }

    /**
     * @return Return the {@code sendThreshold} of the account.
     */
    public long getSendThreshold() {
        return sendThreshold;
    }

    /**
     * @return Return the {@code receiveThreshold} of the account.
     */
    public long getReceiveThreshold() {
        return receiveThreshold;
    }

    /**
     * @return Return the {@code requireSignature} of the account.
     */
    public boolean isRequireSignature() {
        return requireSignature;
    }

    /**
     * @return Return the {@code uid} of the account.
     */
    public long getUid() {
        return uid;
    }
}
