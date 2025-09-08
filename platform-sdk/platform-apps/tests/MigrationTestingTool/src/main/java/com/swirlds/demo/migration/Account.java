// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;

/**
 * This class represents an account being store inside
 * a {@link com.swirlds.virtualmap.VirtualMap} instance.
 */
public class Account {

    private final long balance;
    private final long sendThreshold;
    private final long receiveThreshold;
    private final boolean requireSignature;
    private final long uid;

    public Account(
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

    public Account(final ReadableSequentialData in) {
        this.balance = in.readLong();
        this.sendThreshold = in.readLong();
        this.receiveThreshold = in.readLong();
        this.requireSignature = in.readByte() != 0;
        this.uid = in.readLong();
    }

    /**
     * @return Return {@code 1} if {@code requireSignature} is true, {@code 0} otherwise.
     */
    private byte getRequireSignatureAsByte() {
        return (byte) (requireSignature ? 1 : 0);
    }

    /**
     * @return The total size in bytes of all the fields of this class.
     */
    public int getSizeInBytes() {
        return 4 * Long.BYTES + 1;
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
    public String toString() {
        return "AccountVirtualMapValue{" + "balance="
                + balance + ", sendThreshold="
                + sendThreshold + ", receiveThreshold="
                + receiveThreshold + ", requireSignature="
                + requireSignature + ", uid="
                + uid + '}';
    }
}
