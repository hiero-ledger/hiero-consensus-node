// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import com.hedera.node.app.service.contract.impl.hevm.OpsDurationSchedule;
import java.util.Objects;

/**
 * A helper utility that keeps track of the ops duration throttle
 * throughout the execution of a single EVM transaction.
 */
public final class OpsDurationThrottleUtils {
    private final boolean enabled;
    private final OpsDurationSchedule schedule;
    private final long opsDurationUnitsInitiallyAvailable;

    private long opsDurationUnitsConsumed;

    public static OpsDurationThrottleUtils disabled() {
        return new OpsDurationThrottleUtils(false, OpsDurationSchedule.empty(), 0L, 0L);
    }

    public static OpsDurationThrottleUtils withInitiallyAvailableUnits(
            final OpsDurationSchedule schedule, final long opsDurationUnitsInitiallyAvailable) {
        return new OpsDurationThrottleUtils(true, schedule, opsDurationUnitsInitiallyAvailable, 0L);
    }

    private OpsDurationThrottleUtils(
            final boolean enabled,
            final OpsDurationSchedule schedule,
            final long opsDurationUnitsInitiallyAvailable,
            final long opsDurationUnitsConsumed) {
        this.enabled = enabled;
        this.schedule = schedule;
        this.opsDurationUnitsInitiallyAvailable = opsDurationUnitsInitiallyAvailable;
        this.opsDurationUnitsConsumed = opsDurationUnitsConsumed;
    }

    /**
     * Attempts to consume a given number of ops duration units.
     * If the available amount is insufficient, consumes the remainder
     * (i.e. sets opsDurationUnitsConsumed := opsDurationUnitsInitiallyAvailable)
     * and returns false.
     * No-op if the enabled flag is set to false.
     *
     * @param opsDurationUnitsToConsume the amount of ops duration units to consume
     * @return true if the available amount is less than or equal the amount to consume,
     *   false otherwise. Always returns true if the enabled flag is set to false.
     */
    public boolean tryConsumeOpsDurationUnits(final long opsDurationUnitsToConsume) {
        if (!enabled) {
            return true;
        }

        final var newOpsDurationUnitsConsumed = this.opsDurationUnitsConsumed + opsDurationUnitsToConsume;
        if (newOpsDurationUnitsConsumed <= this.opsDurationUnitsInitiallyAvailable) {
            this.opsDurationUnitsConsumed = newOpsDurationUnitsConsumed;
            return true;
        } else {
            this.opsDurationUnitsConsumed = opsDurationUnitsInitiallyAvailable;
            return false;
        }
    }

    public OpsDurationSchedule schedule() {
        return schedule;
    }

    public long opsDurationUnitsConsumed() {
        return opsDurationUnitsConsumed;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof OpsDurationThrottleUtils that)) return false;
        return enabled == that.enabled
                && opsDurationUnitsInitiallyAvailable == that.opsDurationUnitsInitiallyAvailable
                && opsDurationUnitsConsumed == that.opsDurationUnitsConsumed
                && Objects.equals(schedule, that.schedule);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, schedule, opsDurationUnitsInitiallyAvailable, opsDurationUnitsConsumed);
    }
}
