// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import com.hedera.hapi.node.state.token.NodeRewards;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with node rewards.
 */
public interface ReadableNodeRewardsStore {
    /**
     * Returns the {link NodeRewards} in state.
     *
     * @return the {link NodeRewards} in state
     */
    NodeRewards get();
}
