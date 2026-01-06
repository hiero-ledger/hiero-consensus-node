// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import com.hedera.hapi.node.state.token.StakePeriodTime;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with stake period time.
 */
public interface ReadableStakePeriodTimeStore {
    /**
     * Returns the {@link StakePeriodTime} in state.
     *
     * @return the {@link StakePeriodTime} in state
     */
    StakePeriodTime get();
}

