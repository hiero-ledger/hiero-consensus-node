// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;

/**
 * A record of using throttle capacity.
 * @param throttle the throttle to use
 * @param n the number of transactions to use
 */
public record DeterministicThrottleUsage(DeterministicThrottle throttle, int n) implements ThrottleUsage {
    /**
     * Reclaim the used capacity from the throttle.
     */
    @Override
    public void reclaimCapacity() {
        throttle.leakInstantaneous(n);
    }
}
