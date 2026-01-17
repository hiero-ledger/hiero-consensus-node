// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.swirlds.virtualmap.VirtualMap;
import java.io.IOException;
import java.util.Random;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * A transaction that can be applied to a {@link MigrationTestingToolState}.
 */
public class MigrationTestingToolTransaction implements SelfSerializable {

    private static final long CLASS_ID = 0xf39d77ce3e1f7427L;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private long seed;

    public MigrationTestingToolTransaction() {}

    /**
     * Create a new transaction.
     *
     * @param seed the source of all randomness used by the transaction
     */
    public MigrationTestingToolTransaction(final long seed) {
        this.seed = seed;
    }

    /**
     * Apply this transaction to a state.
     *
     * @param state
     * 		a mutable state
     */
    public void applyToState(final MigrationTestingToolState state) {
        final Random random = new Random(seed);
        final VirtualMap map = (VirtualMap) state.getRoot();

        final AccountID key = new AccountID(0, 0, Math.abs(random.nextLong()));
        final Account value = new Account(
                random.nextLong(), random.nextLong(), random.nextLong(), random.nextBoolean(), random.nextLong());

        map.put(key.toBytes(), value, AccountCodec.INSTANCE);
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
        out.writeLong(seed);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        seed = in.readLong();
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
    public String toString() {
        return "MigrationTestingToolTransaction{" + "seed=" + seed + '}';
    }
}
