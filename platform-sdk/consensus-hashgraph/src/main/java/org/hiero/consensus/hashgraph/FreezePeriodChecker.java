// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph;

import edu.umd.cs.findbugs.annotations.NonNull;
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
    boolean isInFreezePeriod(@NonNull Instant timestamp);
}
