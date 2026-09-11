// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import static com.hedera.node.app.hapi.utils.CommonUtils.clampedAdd;

import com.hedera.node.app.service.contract.impl.hevm.OpsDurationSchedule;
import java.util.Objects;

/**
 * A helper utility that keeps track of the ops duration throttle
 * throughout the execution of a single EVM transaction.
 */
public final class OpsDurationCounter {
    private final OpsDurationSchedule schedule;

    private long opsDurationUnitsConsumed;

    public static OpsDurationCounter disabled() {
        return new OpsDurationCounter(OpsDurationSchedule.empty());
    }

    public static OpsDurationCounter withSchedule(final OpsDurationSchedule schedule) {
        return new OpsDurationCounter(schedule);
    }

    private OpsDurationCounter(final OpsDurationSchedule schedule) {
        this.schedule = schedule;
        this.opsDurationUnitsConsumed = 0L;
    }

    public void recordOpsDurationUnitsConsumed(final long opsDurationUnitsToConsume) {
        if (opsDurationUnitsToConsume <= 0L) {
            // Never record a negative amount; zero is a no-op. This keeps the running total monotonic
            // and non-negative even if a caller ever computes a bad (e.g. overflowed) cost.
            return;
        }
        // Saturating add so the running total can never overflow to a negative value.
        this.opsDurationUnitsConsumed = clampedAdd(this.opsDurationUnitsConsumed, opsDurationUnitsToConsume);
    }

    public OpsDurationSchedule schedule() {
        return schedule;
    }

    public long opsDurationUnitsConsumed() {
        return opsDurationUnitsConsumed;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof OpsDurationCounter that)) return false;
        return opsDurationUnitsConsumed == that.opsDurationUnitsConsumed && Objects.equals(schedule, that.schedule);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schedule, opsDurationUnitsConsumed);
    }
}
