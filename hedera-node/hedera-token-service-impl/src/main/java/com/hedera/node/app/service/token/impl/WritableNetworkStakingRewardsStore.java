// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Default implementation of {@link WritableNetworkStakingRewardsStore}.
 */
public class WritableNetworkStakingRewardsStore extends ReadableNetworkStakingRewardsStoreImpl {

    /** The underlying data storage class that holds staking reward data for all nodes. */
    private final WritableSingletonState<NetworkStakingRewards> stakingRewardsState;

    /**
     * Create a new {@link WritableNetworkStakingRewardsStore} instance.
     *
     * @param states The state to use.
     */
    public WritableNetworkStakingRewardsStore(@NonNull final WritableStates states) {
        super(states);
        this.stakingRewardsState = requireNonNull(states).getSingleton(STAKING_NETWORK_REWARDS_STATE_ID);
    }

    /**
     * Persists the staking rewards data to the underlying storage.
     * @param stakingRewards The staking rewards data to persist.
     */
    public void put(@NonNull final NetworkStakingRewards stakingRewards) {
        requireNonNull(stakingRewards);
        stakingRewardsState.put(stakingRewards);
    }
}
