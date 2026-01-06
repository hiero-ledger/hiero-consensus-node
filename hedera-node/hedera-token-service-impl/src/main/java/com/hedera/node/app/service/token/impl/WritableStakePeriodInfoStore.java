// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0710TokenSchema.STAKE_PERIOD_INFO_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.StakePeriodInfo;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Default writable implementation for stake period info.
 */
public class WritableStakePeriodInfoStore extends ReadableStakePeriodInfoStoreImpl {

    /**
     * The underlying data storage class that holds stake period info data.
     */
    private final WritableSingletonState<StakePeriodInfo> stakePeriodInfoState;

    /**
     * Create a new {@link WritableStakePeriodInfoStore} instance.
     *
     * @param states The state to use.
     */
    public WritableStakePeriodInfoStore(@NonNull final WritableStates states) {
        super(states);
        this.stakePeriodInfoState = requireNonNull(states).getSingleton(STAKE_PERIOD_INFO_STATE_ID);
    }

    /**
     * Persists the stake period info data to the underlying storage.
     *
     * @param stakePeriodInfo The stake period info data to persist.
     */
    public void put(@NonNull final StakePeriodInfo stakePeriodInfo) {
        requireNonNull(stakePeriodInfo);
        stakePeriodInfoState.put(stakePeriodInfo);
    }

    /**
     * Updates the last stake period calculation time.
     *
     * @param lastStakePeriodCalculationTime The timestamp of the last stake period calculation.
     */
    public void updateLastStakePeriodCalculationTime(@NonNull final Timestamp lastStakePeriodCalculationTime) {
        requireNonNull(lastStakePeriodCalculationTime);
        stakePeriodInfoState.put(StakePeriodInfo.newBuilder()
                .lastStakePeriodCalculationTime(lastStakePeriodCalculationTime)
                .build());
    }
}

