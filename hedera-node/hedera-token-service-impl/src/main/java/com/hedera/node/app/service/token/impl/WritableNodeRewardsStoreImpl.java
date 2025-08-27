// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.NODE_REWARDS_KEY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Default implementation of {@link ReadableNetworkStakingRewardsStore}.
 */
public class WritableNodeRewardsStoreImpl extends ReadableNodeRewardsStoreImpl {

    /**
     * The underlying data storage class that holds staking reward data for all nodes.
     */
    private final WritableSingletonState<NodeRewards> nodeRewardsState;

    /**
     * Create a new {@link WritableNodeRewardsStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public WritableNodeRewardsStoreImpl(@NonNull final WritableStates states) {
        super(states);
        this.nodeRewardsState = requireNonNull(states).getSingleton(NODE_REWARDS_KEY);
    }

    /**
     * Persists the node rewards data to the underlying storage.
     *
     * @param nodeRewards The node rewards data to persist.
     */
    public void put(@NonNull final NodeRewards nodeRewards) {
        requireNonNull(nodeRewards);
        nodeRewardsState.put(nodeRewards);
    }

    /**
     * Resets the node rewards state for a new payment period.
     */
    public void resetForNewStakingPeriod() {
        nodeRewardsState.put(NodeRewards.newBuilder()
                .numRoundsInStakingPeriod(0)
                .nodeFeesCollected(0)
                .nodeActivities(List.of())
                .build());
    }
}
