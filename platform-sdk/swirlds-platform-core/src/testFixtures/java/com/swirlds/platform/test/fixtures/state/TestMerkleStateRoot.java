// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.merkle.MerkleStateRoot;

/**
 * @deprecated This test class is only required for the testing of MerkleStateRoot class and will be removed together with that class.
 */
@Deprecated
public class TestMerkleStateRoot extends MerkleStateRoot<TestMerkleStateRoot> implements MerkleNodeState {

    private static final long CLASS_ID = 0x8e300b0dfdafbb1cL;

    public TestMerkleStateRoot() {
        // empty constructor
    }

    protected TestMerkleStateRoot(TestMerkleStateRoot that) {
        super(that);
    }

    @Override
    protected TestMerkleStateRoot copyingConstructor() {
        return new TestMerkleStateRoot(this);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }
}
