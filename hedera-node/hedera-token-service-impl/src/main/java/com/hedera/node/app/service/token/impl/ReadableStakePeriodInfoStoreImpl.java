// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.STAKE_PERIOD_INFO_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.token.StakePeriodInfo;
import com.hedera.node.app.service.token.ReadableStakePeriodInfoStore;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Default implementation of {@link ReadableStakePeriodInfoStore}.
 */
public class ReadableStakePeriodInfoStoreImpl implements ReadableStakePeriodInfoStore {

    /**
     * The underlying data storage class that holds stake period info data.
     */
    private final ReadableSingletonState<StakePeriodInfo> stakePeriodInfoState;

    /**
     * Create a new {@link ReadableStakePeriodInfoStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableStakePeriodInfoStoreImpl(@NonNull final ReadableStates states) {
        this.stakePeriodInfoState = requireNonNull(states).getSingleton(STAKE_PERIOD_INFO_STATE_ID);
    }

    @Override
    public StakePeriodInfo get() {
        return requireNonNull(stakePeriodInfoState.get());
    }
}
