// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.demo.virtualmerkle.random.PTTRandom;
import com.swirlds.virtualmap.VirtualValue;
import java.io.IOException;
import java.util.Arrays;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * This class holds the bytecode for a smart contract.
 * The bytecode is stored as an array of random bytes.
 */
public final class SmartContractByteCodeMapValue implements VirtualValue {

    public static final int MAX_BYTE_CODE_BYTES = 12_000;

    private static final long CLASS_ID = 0x546152f3fa969872L;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private byte[] byteCode;

    /**
     * Builds an empty bytecode value.
     */
    public SmartContractByteCodeMapValue() {
        byteCode = new byte[0];
    }

    /**
     * Builds a {@link SmartContractByteCodeMapValue} from another
     * {@link SmartContractByteCodeMapValue} instance.
     *
     * @param source
     * 		A {@link SmartContractByteCodeMapValue} instance.
     */
    public SmartContractByteCodeMapValue(final SmartContractByteCodeMapValue source) {
        byteCode = Arrays.copyOf(source.byteCode, source.byteCode.length);
    }

    /**
     * @param pttRandom
     * 		A {@link PTTRandom} instance to compute the bytes for the bytecode.
     * @param byteCodeSize
     * 		The size in bytes for the bytecode.
     */
    public SmartContractByteCodeMapValue(final PTTRandom pttRandom, final int byteCodeSize) {
        byteCode = new byte[byteCodeSize];
        for (int i = 0; i < byteCodeSize; i++) {
            byteCode[i] = pttRandom.nextByte();
        }
    }

    public SmartContractByteCodeMapValue(final ReadableSequentialData in) {
        final int len = in.readInt();
        byteCode = new byte[len];
        in.readBytes(byteCode);
    }

    public Bytes toBytes() {
        final byte[] bytes = new byte[getSizeInBytes()];
        writeTo(BufferedData.wrap(bytes));
        return Bytes.wrap(bytes);
    }

    /**
     * @param bytes
     * 		specific data to place into an instance of this class
     */
    public SmartContractByteCodeMapValue(final byte[] bytes) {
        byteCode = Arrays.copyOf(bytes, bytes.length);
    }

    public void writeTo(final WritableSequentialData out) {
        out.writeInt(byteCode.length);
        out.writeBytes(byteCode);
    }

    /**
     * Returns the size of this smart contract byte code value, in bytes.
     *
     * @return
     * 		The size in bytes
     */
    public int getSize() {
        return byteCode.length;
    }

    /**
     * Returns this smart contract byte code value as a byte array.
     *
     * @return
     * 		Smart contract byte code value as a byte array
     */
    public byte[] getByteCode() {
        return byteCode;
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
    public SmartContractByteCodeMapValue copy() {
        return new SmartContractByteCodeMapValue(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmartContractByteCodeMapValue asReadOnly() {
        return new SmartContractByteCodeMapValue(this);
    }

    int getSizeInBytes() {
        return Integer.BYTES + byteCode.length;
    }

    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeByteArray(byteCode);
    }

    void serialize(final WritableSequentialData out) {
        out.writeInt(byteCode.length);
        out.writeBytes(byteCode);
    }

    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        byteCode = in.readByteArray(MAX_BYTE_CODE_BYTES);
    }

    void deserialize(final ReadableSequentialData in) {
        final int len = in.readInt();
        byteCode = new byte[len];
        in.readBytes(byteCode);
    }

    @Override
    public String toString() {
        return "SmartContractByteCodeMapValue{" + "byteCode.length=" + byteCode.length + '}';
    }
}
