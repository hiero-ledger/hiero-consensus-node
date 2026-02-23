// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Default implementation of {@link ReadableNetworkStakingRewardsStore}.
 */
public class ReadableNetworkStakingRewardsStoreImpl implements ReadableNetworkStakingRewardsStore {

    /** The underlying data storage class that holds staking reward data for all nodes. */
    private final ReadableSingletonState<NetworkStakingRewards> stakingRewardsState;

    /**
     * Create a new {@link ReadableNetworkStakingRewardsStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableNetworkStakingRewardsStoreImpl(@NonNull final ReadableStates states) {
        this.stakingRewardsState = requireNonNull(states).getSingleton(STAKING_NETWORK_REWARDS_STATE_ID);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isStakingRewardsActivated() {
        return requireNonNull(stakingRewardsState.get()).stakingRewardsActivated();
    }

    /** {@inheritDoc} */
    @Override
    public long totalStakeRewardStart() {
        return requireNonNull(stakingRewardsState.get()).totalStakedRewardStart();
    }

    /** {@inheritDoc} */
    @Override
    public long totalStakedStart() {
        return requireNonNull(stakingRewardsState.get()).totalStakedStart();
    }

    /** {@inheritDoc} */
    @Override
    public long pendingRewards() {
        return requireNonNull(stakingRewardsState.get()).pendingRewards();
    }

    @Override
    public NetworkStakingRewards get() {
        return requireNonNull(stakingRewardsState.get());
    }
}
