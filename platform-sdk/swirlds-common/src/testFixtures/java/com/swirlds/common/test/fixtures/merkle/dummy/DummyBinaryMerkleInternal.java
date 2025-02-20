// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.dummy;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;

/**
 * A binary merkle node for testing purposes.
 */
public class DummyBinaryMerkleInternal extends PartialBinaryMerkleInternal implements MerkleInternal {

    private static final long CLASS_ID = 0x1a2da64b35e8430fL;
    private static final int VERSION = 1;

    public DummyBinaryMerkleInternal() {}

    private DummyBinaryMerkleInternal(final DummyBinaryMerkleInternal that) {
        super(that);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DummyBinaryMerkleInternal copy() {
        return new DummyBinaryMerkleInternal(this);
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
        return VERSION;
    }
}
