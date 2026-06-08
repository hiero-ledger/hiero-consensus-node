// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default implementation of {@link WritableNetworkStakingRewardsStore}.
 */
public class WritableNetworkStakingRewardsStore extends ReadableNetworkStakingRewardsStoreImpl {
    private static final Logger log = LogManager.getLogger(WritableNetworkStakingRewardsStore.class);

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
        logNetworkStakingRewardsPut(stakingRewardsState.get(), stakingRewards);
        stakingRewardsState.put(stakingRewards);
    }

    private static void logNetworkStakingRewardsPut(
            @NonNull final NetworkStakingRewards previous, @NonNull final NetworkStakingRewards next) {
        log.info(
                "Forensic token singleton put TokenService.STAKING_NETWORK_REWARDS noOp={} before={} after={}",
                Objects.equals(previous, next),
                networkStakingRewardsSummary(previous),
                networkStakingRewardsSummary(next));
    }

    private static String networkStakingRewardsSummary(@NonNull final NetworkStakingRewards rewards) {
        return "activated=%s totalStakedStart=%d totalStakedRewardStart=%d pendingRewards=%d hashCode=%d"
                .formatted(
                        rewards.stakingRewardsActivated(),
                        rewards.totalStakedStart(),
                        rewards.totalStakedRewardStart(),
                        rewards.pendingRewards(),
                        rewards.hashCode());
    }
}
