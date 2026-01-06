// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0710TokenSchema.STAKE_PERIOD_TIME_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.token.StakePeriodTime;
import com.hedera.node.app.service.token.ReadableStakePeriodTimeStore;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Default implementation of {@link ReadableStakePeriodTimeStore}.
 */
public class ReadableStakePeriodTimeStoreImpl implements ReadableStakePeriodTimeStore {

    /**
     * The underlying data storage class that holds stake period time data.
     */
    private final ReadableSingletonState<StakePeriodTime> stakePeriodTimeState;

    /**
     * Create a new {@link ReadableStakePeriodTimeStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableStakePeriodTimeStoreImpl(@NonNull final ReadableStates states) {
        this.stakePeriodTimeState = requireNonNull(states).getSingleton(STAKE_PERIOD_TIME_STATE_ID);
    }

    @Override
    public StakePeriodTime get() {
        return requireNonNull(stakePeriodTimeState.get());
    }
}
