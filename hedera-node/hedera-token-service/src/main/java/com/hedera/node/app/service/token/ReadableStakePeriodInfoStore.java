// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import com.hedera.hapi.node.state.token.StakePeriodInfo;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with stake period info.
 */
public interface ReadableStakePeriodInfoStore {
    /**
     * Returns the {@link StakePeriodInfo} in state.
     *
     * @return the {@link StakePeriodInfo} in state
     */
    StakePeriodInfo get();
}
