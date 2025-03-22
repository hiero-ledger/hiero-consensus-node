// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.pta;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import java.io.IOException;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A merkle wrapper around {@link MapKey}.
 */
public class MerkleMapKey extends PartialMerkleLeaf implements MerkleLeaf {

    public static final long CLASS_ID = 0x66ff4872c3ae8c5fL;

    private static final class ClassVersion {

        public static final int ORIGINAL = 1;
    }

    private MapKey mapKey;

    public MerkleMapKey() {}

    public MerkleMapKey(final MapKey mapKey) {
        this.mapKey = mapKey;
    }

    private MerkleMapKey(final MerkleMapKey that) {
        this.mapKey = that.mapKey.copy();
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
     * Get the map key.
     */
    public MapKey getMapKey() {
        return mapKey;
    }

    /**
     * Set the map key.
     */
    public void setMapKey(final MapKey mapKey) {
        this.mapKey = mapKey;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleMapKey copy() {
        return new MerkleMapKey(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeSerializable(mapKey, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        mapKey = in.readSerializable(true, MapKey::new);
    }
}
