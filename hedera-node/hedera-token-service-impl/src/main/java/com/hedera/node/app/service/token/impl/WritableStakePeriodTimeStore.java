// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0710TokenSchema.STAKE_PERIOD_TIME_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.StakePeriodTime;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Default writable implementation for stake period time.
 */
public class WritableStakePeriodTimeStore extends ReadableStakePeriodTimeStoreImpl {

    /**
     * The underlying data storage class that holds stake period time data.
     */
    private final WritableSingletonState<StakePeriodTime> stakePeriodTimeState;

    /**
     * Create a new {@link WritableStakePeriodTimeStore} instance.
     *
     * @param states The state to use.
     */
    public WritableStakePeriodTimeStore(@NonNull final WritableStates states) {
        super(states);
        this.stakePeriodTimeState = requireNonNull(states).getSingleton(STAKE_PERIOD_TIME_STATE_ID);
    }

    /**
     * Persists the stake period time data to the underlying storage.
     *
     * @param stakePeriodTime The stake period time data to persist.
     */
    public void put(@NonNull final StakePeriodTime stakePeriodTime) {
        requireNonNull(stakePeriodTime);
        stakePeriodTimeState.put(stakePeriodTime);
    }

    /**
     * Updates the last stake period update time.
     *
     * @param lastStakePeriodUpdateTime The timestamp of the last stake period update.
     */
    public void updateLastStakePeriodUpdateTime(@NonNull final Timestamp lastStakePeriodUpdateTime) {
        requireNonNull(lastStakePeriodUpdateTime);
        stakePeriodTimeState.put(StakePeriodTime.newBuilder()
                .lastStakePeriodUpdateTime(lastStakePeriodUpdateTime)
                .build());
    }
}

