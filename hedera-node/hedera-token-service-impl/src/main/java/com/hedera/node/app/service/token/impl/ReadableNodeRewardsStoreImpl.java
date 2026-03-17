// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.NODE_REWARDS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.node.app.service.token.ReadableNodeRewardsStore;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Default implementation of {@link ReadableNodeRewardsStore}.
 */
public class ReadableNodeRewardsStoreImpl implements ReadableNodeRewardsStore {

    /**
     * The underlying data storage class that holds node rewards data for all nodes.
     */
    private final ReadableSingletonState<NodeRewards> nodeRewardsState;

    /**
     * Create a new {@link ReadableNodeRewardsStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableNodeRewardsStoreImpl(@NonNull final ReadableStates states) {
        this.nodeRewardsState = requireNonNull(states).getSingleton(NODE_REWARDS_STATE_ID);
    }

    @Override
    public NodeRewards get() {
        return requireNonNull(nodeRewardsState.get());
    }
}
