// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import com.hedera.node.app.service.contract.impl.hevm.OpsDurationSchedule;
import java.util.Objects;

/**
 * A helper utility that keeps track of the ops duration throttle
 * throughout the execution of a single EVM transaction.
 */
public final class OpsDurationCounter {

    private static final OpsDurationCounter DISABLED = new OpsDurationCounter(OpsDurationSchedule.empty());

    private final OpsDurationSchedule schedule;

    private long opsDurationUnitsConsumed;

    public static OpsDurationCounter disabled() {
        return DISABLED;
    }

    public static OpsDurationCounter withSchedule(final OpsDurationSchedule schedule) {
        return new OpsDurationCounter(schedule);
    }

    private OpsDurationCounter(final OpsDurationSchedule schedule) {
        this.schedule = schedule;
        this.opsDurationUnitsConsumed = 0L;
    }

    public void recordOpsDurationUnitsConsumed(final long opsDurationUnitsToConsume) {
        this.opsDurationUnitsConsumed += opsDurationUnitsToConsume;
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
