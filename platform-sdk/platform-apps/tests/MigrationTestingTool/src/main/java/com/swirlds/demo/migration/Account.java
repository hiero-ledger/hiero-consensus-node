// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;

/**
 * This record represents an account stored in a
 * {@link com.swirlds.virtualmap.VirtualMap} instance.
 */
public record Account(long balance, long sendThreshold, long receiveThreshold, boolean requireSignature, long uid) {

    public Account() {
        this(0L, 0L, 0L, false, 0L);
    }

    public Account(final ReadableSequentialData in) {
        this(in.readLong(), in.readLong(), in.readLong(), in.readByte() != 0, in.readLong());
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
