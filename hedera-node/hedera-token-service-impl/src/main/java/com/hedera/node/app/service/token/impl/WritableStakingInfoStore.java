// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides write methods for modifying underlying data storage mechanisms for working with
 * a node's staking information.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 */
public class WritableStakingInfoStore extends ReadableStakingInfoStoreImpl {

    /** The underlying data storage class that holds the staking data. */
    private final WritableKVState<EntityNumber, StakingNodeInfo> stakingInfoState;

    private final WritableEntityCounters entityCounters;

    /**
     * Create a new {@link WritableStakingInfoStore} instance.
     *
     * @param states         The state to use
     * @param entityCounters
     */
    public WritableStakingInfoStore(@NonNull final WritableStates states, final WritableEntityCounters entityCounters) {
        super(states, entityCounters);
        requireNonNull(states);

        this.stakingInfoState = states.get(V0490TokenSchema.STAKING_INFO_KEY);
        this.entityCounters = requireNonNull(entityCounters);
    }

    /**
     * Persists an updated {@link StakingNodeInfo} into state.
     *
     * @param nodeId the node's ID
     * @param stakingNodeInfo the staking info to persist
     */
    public void put(final long nodeId, @NonNull final StakingNodeInfo stakingNodeInfo) {
        requireNonNull(stakingNodeInfo);
        stakingInfoState.put(EntityNumber.newBuilder().number(nodeId).build(), stakingNodeInfo);
    }

    /**
     * Persists a new {@link StakingNodeInfo} into state and increments the entity counter for staking info.
     * @param nodeId the node's ID
     * @param stakingNodeInfo the staking info to persist
     */
    public void putAndIncrementCount(final long nodeId, @NonNull final StakingNodeInfo stakingNodeInfo) {
        put(nodeId, stakingNodeInfo);
        entityCounters.incrementEntityTypeCount(EntityType.STAKING_INFO);
    }

    /**
     * Gets the original value associated with the given nodeId before any modifications were made to
     * it. The returned value will be {@code null} if the nodeId does not exist.
     *
     * @param nodeId The nftId.
     * @return The original value, or null if there is no such nftId in the state
     */
    @Nullable
    public StakingNodeInfo getOriginalValue(final long nodeId) {
        return stakingInfoState.getOriginalValue(
                EntityNumber.newBuilder().number(nodeId).build());
    }
}
