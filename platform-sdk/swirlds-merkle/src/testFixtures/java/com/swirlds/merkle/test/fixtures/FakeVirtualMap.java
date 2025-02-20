// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;

/**
 * Imitates a virtual map. Useful for spoofing the virtual map tree structure during a reconnect test.
 */
public class FakeVirtualMap extends PartialBinaryMerkleInternal implements MerkleInternal {

    /**
     * Used for serialization.
     */
    public static final long CLASS_ID = 0xb881f3704885e854L;

    public static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    @Override
    public FakeVirtualMap copy() {
        throw new UnsupportedOperationException();
    }
}
