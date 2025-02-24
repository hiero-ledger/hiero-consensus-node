// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.dummy;

/**
 * The exact same behavior as a DummyMerkleInternal but with a different class Id.
 */
public class DummyMerkleInternal2 extends DummyMerkleInternal {

    protected final long classId = 0x9876fcbbL;

    public DummyMerkleInternal2() {
        super();
    }

    public DummyMerkleInternal2(String value) {
        super(value);
    }

    @Override
    public long getClassId() {
        return classId;
    }
}
