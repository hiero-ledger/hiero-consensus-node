// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.freeze;

import java.time.Instant;

/**
 * Checks whether a timestamp is in freeze period
 */
public interface FreezePeriodChecker {
    /**
     * Checks whether the given instant is in the freeze period
     * Only when the timestamp is not before freezeTime, and freezeTime is after lastFrozenTime,
     * the timestamp is in the freeze period.
     *
     * @param timestamp
     * 		an Instant to check
     * @return true if it is in the freeze period, false otherwise
     */
    boolean isInFreezePeriod(Instant timestamp);
}
