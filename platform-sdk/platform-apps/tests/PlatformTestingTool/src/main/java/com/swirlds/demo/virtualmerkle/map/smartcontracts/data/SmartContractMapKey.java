// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.map.smartcontracts.data;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.VirtualKey;
import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A smart contract has a list of key value pairs to store its data.
 * Each pair has a key and a value. A key is made of the {@code contractId}
 * and the {@code keyValuePairIndex} which is its index on the
 * smart contract list.
 */
public final class SmartContractMapKey implements VirtualKey {
    private static final long CLASS_ID = 0x3760716d0ab5b622L;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private long contractId;

    private long keyValuePairIndex;

    /**
     * Builds a {@link SmartContractMapKey} with default values.
     */
    public SmartContractMapKey() {
        this(0L, 0L);
    }

    /**
     * Builds a {@link SmartContractMapKey} for the contract with id  {@code contractId},
     * and key value pair with index {@code keyValuePairIndex}.
     *
     * @param contractId
     * @param keyValuePairIndex
     */
    public SmartContractMapKey(final long contractId, final long keyValuePairIndex) {
        this.contractId = contractId;
        this.keyValuePairIndex = keyValuePairIndex;
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

    boolean equals(final BufferedData buffer) {
        return contractId == buffer.readLong() && keyValuePairIndex == buffer.readLong();
    }

    static int getSizeInBytes() {
        return 2 * Long.BYTES;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeLong(contractId);
        out.writeLong(keyValuePairIndex);
    }

    void serialize(final WritableSequentialData out) {
        out.writeLong(contractId);
        out.writeLong(keyValuePairIndex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        contractId = in.readLong();
        keyValuePairIndex = in.readLong();
    }

    void deserialize(final ReadableSequentialData in) {
        contractId = in.readLong();
        keyValuePairIndex = in.readLong();
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
        final SmartContractMapKey that = (SmartContractMapKey) other;
        return contractId == that.contractId && keyValuePairIndex == that.keyValuePairIndex;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(contractId, keyValuePairIndex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "SmartContractMapKey{" + "contractId=" + contractId + ", keyValuePairIndex=" + keyValuePairIndex + '}';
    }
}
