// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test;

import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * This self serializable object always throws a null pointer exception when deserialized.
 */
public class ExplodingSelfSerializable implements SelfSerializable {

    private static final long CLASS_ID = 0xa2936a853db7326eL;

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) {
        throw new UnsupportedOperationException("this method intentionally throws an exception");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) {}

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
}
