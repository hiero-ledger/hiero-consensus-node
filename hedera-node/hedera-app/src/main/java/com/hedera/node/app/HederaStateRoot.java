// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This class sole purpose is to extend the {@link MerkleStateRoot} class and implement the {@link MerkleNodeState}.
 * Technically, {@link MerkleStateRoot} is already implementing {@link State} and {@link MerkleNode} but it does not
 * implement the {@link MerkleNodeState} interface. This class is merely a connector of these two interfaces.
 *
 * @deprecated This class should be removed together with {@link MerkleStateRoot}.
 */
@Deprecated
public class HederaStateRoot extends MerkleStateRoot<HederaStateRoot> implements MerkleNodeState {

    private static final long CLASS_ID = 0x8e300b0dfdafbb1aL;

    public HederaStateRoot() {
        // empty
    }

    protected HederaStateRoot(@NonNull HederaStateRoot from) {
        super(from);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected HederaStateRoot copyingConstructor() {
        return new HederaStateRoot(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }
}
