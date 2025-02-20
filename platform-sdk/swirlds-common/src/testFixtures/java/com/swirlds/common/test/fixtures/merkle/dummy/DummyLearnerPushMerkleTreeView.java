// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.dummy;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.views.LearnerPushMerkleTreeView;
import edu.umd.cs.findbugs.annotations.NonNull;

public class DummyLearnerPushMerkleTreeView extends LearnerPushMerkleTreeView {

    /**
     * Create a new standard tree view out of an in-memory merkle tree (or subtree).
     *
     * @param root
     * 		the root of the tree (or subtree)
     * @param mapStats
     *      a ReconnectMapStats object to collect reconnect metrics
     */
    public DummyLearnerPushMerkleTreeView(
            final ReconnectConfig reconnectConfig, final MerkleNode root, @NonNull final ReconnectMapStats mapStats) {
        super(reconnectConfig, root, mapStats);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRootOfState() {
        return false;
    }
}
