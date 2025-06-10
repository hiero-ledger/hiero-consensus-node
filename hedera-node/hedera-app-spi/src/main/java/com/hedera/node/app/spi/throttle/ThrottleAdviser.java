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
     * Verifies if the current ops duration value breaches the ops duration throttle.
     *
     * @param currentOpsDuration the ops duration consumed by the transaction so far
     */
    boolean shouldThrottleByOpsDuration(final long currentOpsDuration);
}
