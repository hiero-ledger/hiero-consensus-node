// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.swirlds.demo.migration.virtual.AccountVirtualMapKey;
import com.swirlds.demo.migration.virtual.AccountVirtualMapValue;
import com.swirlds.demo.migration.virtual.AccountVirtualMapValueCodec;
import com.swirlds.merkle.map.MerkleMap;
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

    public enum TransactionType {
        /**
         * Insert a random value into a merkle map.
         */
        MERKLE_MAP,
        /**
         * Insert a random value into a virtual map.
         */
        VIRTUAL_MAP
    }

    private TransactionType type;
    private long seed;

    public MigrationTestingToolTransaction() {}

    /**
     * Create a new transaction.
     *
     * @param type
     * 		the type of the transaction
     * @param seed
     * 		the source of all randomness used by the transaction
     */
    public MigrationTestingToolTransaction(final TransactionType type, final long seed) {
        this.type = type;
        this.seed = seed;
    }

    /**
     * Apply this transaction to a state.
     *
     * @param state
     * 		a mutable state
     */
    public void applyTo(final MigrationTestingToolState state) {
        final Random random = new Random(seed);
        switch (type) {
            case MERKLE_MAP:
                applyMerkleMapTransaction(state, random);
                break;
            case VIRTUAL_MAP:
                applyVirtualMapTransaction(state, random);
                break;
            default:
                throw new IllegalStateException("unhandled type " + type);
        }
    }

    /**
     * Perform a {@link TransactionType#MERKLE_MAP} transaction.
     */
    private void applyMerkleMapTransaction(final MigrationTestingToolState state, final Random random) {
        final MerkleMap<AccountID, MapValue> map = state.getMerkleMap();

        final AccountID key = new AccountID(0, 0, Math.abs(random.nextLong()));
        final MapValue value = MapValue.generateRandom(random, key);

        map.put(key, value);
    }

    /**
     * Perform a {@link TransactionType#VIRTUAL_MAP} transaction.
     */
    private void applyVirtualMapTransaction(final MigrationTestingToolState state, final Random random) {
        final VirtualMap map = state.getVirtualMap();

        final AccountVirtualMapKey key = new AccountVirtualMapKey(0, 0, Math.abs(random.nextLong()));
        final AccountVirtualMapValue value = new AccountVirtualMapValue(
                random.nextLong(), random.nextLong(), random.nextLong(), random.nextBoolean(), random.nextLong());

        map.put(key.toBytes(), value, AccountVirtualMapValueCodec.INSTANCE);
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
        out.writeInt(type.ordinal());
        out.writeLong(seed);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        type = TransactionType.values()[in.readInt()];
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
        return "MigrationTestingToolTransaction{" + "type=" + type + ", seed=" + seed + '}';
    }
}
