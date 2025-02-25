// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.dummy;

/**
 * The exact same behavior as a DummyMerkleLeaf but with a different class Id.
 */
public class DummyMerkleLeaf2 extends DummyMerkleLeaf {

    public DummyMerkleLeaf2() {
        super();
    }

    public DummyMerkleLeaf2(String value) {
        super(value);
    }

    @Override
    public long getClassId() {
        return super.getClassId() + 1;
    }
}
