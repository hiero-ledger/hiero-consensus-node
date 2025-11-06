// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.throttle;

import com.hedera.hapi.node.base.HederaFunctionality;

public interface ThrottleAdviser {

    /**
     * Verifies if the throttle in this operation context has enough capacity to handle the given number of the
     * given function at the given time. (The time matters because we want to consider how much
     * will have leaked between now and that time.)
     *
     * @param n the number of the given function
     * @param function the function
     * @return true if the system should throttle the given number of the given function
     * at the instant for which throttling should be calculated
     */
    boolean shouldThrottleNOfUnscaled(int n, HederaFunctionality function);

    /**
     * Returns the available ops duration capacity for the execution.
     * Takes into account the amount leaked from the bucket up to the current consensus time.
     */
    long availableOpsDurationCapacity();

    /**
     * Consumes a given amount of ops duration units from the throttle's capacity.
     * Takes into account the amount leaked from the bucket up to the current consensus time.
     * If the amount to consume is greater than the available amount then consumes
     * the available amount and returns (does not fail).
     */
    void consumeOpsDurationThrottleCapacity(long opsDurationUnitsToConsume);
}
