// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.benchmark;

import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * An account key for an MerkleMap benchmark.
 */
public class BenchmarkKey implements SelfSerializable {

    private static final long CLASS_ID = 0x41db5e9d036e36feL;

    /**
     * The actual key.
     */
    private long key1;

    /**
     * Not really used for anything, but makes this key more similar in size to a real key in hedera.
     */
    private long key2;

    /**
     * Not really used for anything, but makes this key more similar in size to a real key in hedera.
     */
    private long key3;

    public BenchmarkKey() {}

    public BenchmarkKey(final long key) {
        key1 = key;
        key2 = key + 1;
        key3 = key + 2;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        key1 = in.readLong();
        key2 = in.readLong();
        key3 = in.readLong();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeLong(key1);
        out.writeLong(key2);
        out.writeLong(key3);
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
        return 1;
    }

    /**
     * For the purpose of the benchmark, we ensure that key1 is unique across all keys, so it is safe to use key1
     * as a unique identifier for a key.
     */
    public long getValue() {
        return key1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final BenchmarkKey that = (BenchmarkKey) o;
        return key1 == that.key1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(key1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "(" + key1 + ", " + key2 + ", " + key3 + ")";
    }
}
