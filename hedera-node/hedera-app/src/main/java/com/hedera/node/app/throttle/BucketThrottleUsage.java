// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import com.hedera.node.app.hapi.utils.throttles.LeakyBucketDeterministicThrottle;

/**
 * A record of using a single leaky bucket's throttle capacity.
 * @param throttle the throttle used
 * @param amount the amount of gas used
 */
public record BucketThrottleUsage(LeakyBucketDeterministicThrottle throttle, long amount) implements ThrottleUsage {
    /**
     * Reclaim the used capacity from the throttle.
     */
    @Override
    public void reclaimCapacity() {
        throttle.leakUnusedGasPreviouslyReserved(amount);
    }
}
