// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import com.hedera.node.app.hapi.utils.throttles.GasLimitDeterministicThrottle;

/**
 * A record of using gas throttle capacity.
 * @param throttle the throttle to use
 * @param amount the amount of gas used
 */
public record GasThrottleUsage(GasLimitDeterministicThrottle throttle, long amount) implements ThrottleUsage {
    /**
     * Reclaim the used capacity from the throttle.
     */
    @Override
    public void reclaimCapacity() {
        throttle.leakUnusedGasPreviouslyReserved(amount);
    }
}
