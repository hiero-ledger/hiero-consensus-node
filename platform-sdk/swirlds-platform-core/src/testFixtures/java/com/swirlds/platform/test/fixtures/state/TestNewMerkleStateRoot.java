// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.merkle.NewStateRoot;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

public class TestNewMerkleStateRoot extends NewStateRoot<TestNewMerkleStateRoot> implements MerkleNodeState {

    public TestNewMerkleStateRoot(@NonNull final Configuration configuration) {
        super(configuration);
    }

    public TestNewMerkleStateRoot(@NonNull final VirtualMap virtualMap) {
        super(virtualMap);
    }

    protected TestNewMerkleStateRoot(@NonNull final TestNewMerkleStateRoot from) {
        super(from);
    }
    /**
     * {@inheritDoc}
     */
    @Override
    protected TestNewMerkleStateRoot copyingConstructor() {
        return new TestNewMerkleStateRoot(this);
    }

    @Override
    protected TestNewMerkleStateRoot newInstance(@NonNull final VirtualMap virtualMap) {
        return new TestNewMerkleStateRoot(virtualMap);
    }
}
