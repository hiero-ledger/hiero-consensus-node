// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks whether a timestamp is in freeze period
 */
public class FreezePeriodChecker {

    @Nullable
    private volatile Instant freezeTime;

    public FreezePeriodChecker(@Nullable final Instant freezeTime) {
        this.freezeTime = freezeTime;
    }

    /**
     * Checks whether the given instant is in the freeze period
     * Only when the timestamp is not before freezeTime, and freezeTime is after lastFrozenTime,
     * the timestamp is in the freeze period.
     *
     * @param timestamp
     * 		an Instant to check
     * @return true if it is in the freeze period, false otherwise
     */
    public boolean isInFreezePeriod(@NonNull final Instant timestamp) {
        final Instant localFreezeTime = freezeTime;
        return localFreezeTime != null && localFreezeTime.isBefore(timestamp);
    }

    /**
     * Sets the freeze time. When consensus time is equal to or later than the freeze time, the
     * consensus node will freeze and not make any more progress.
     *
     * <p>Passing {@code null} to this method clears the freeze time and effectively cancels the
     * freeze, assuming the round that causes the previously set freeze time to be crossed has not already
     * reached consensus.
     *
     * @param freezeTime the new freeze time
     */
    public void setFreezeTime(@Nullable final Instant freezeTime) {
        this.freezeTime = freezeTime;
    }
}
