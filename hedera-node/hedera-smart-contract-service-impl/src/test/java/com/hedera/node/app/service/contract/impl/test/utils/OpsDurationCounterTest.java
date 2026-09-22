// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.service.contract.impl.exec.utils.OpsDurationCounter;
import com.hedera.node.app.service.contract.impl.hevm.OpsDurationSchedule;
import org.junit.jupiter.api.Test;

class OpsDurationCounterTest {
    @Test
    void testRecordOpsDurationConsumed() {
        final var schedule = mock(OpsDurationSchedule.class);
        OpsDurationCounter opsDurationCounter = OpsDurationCounter.withSchedule(schedule);
        opsDurationCounter.recordOpsDurationUnitsConsumed(25L);
        assertEquals(25L, opsDurationCounter.opsDurationUnitsConsumed());
    }

    @Test
    void negativeAndZeroAmountsAreIgnored() {
        final var schedule = mock(OpsDurationSchedule.class);
        final var opsDurationCounter = OpsDurationCounter.withSchedule(schedule);
        opsDurationCounter.recordOpsDurationUnitsConsumed(10L);
        opsDurationCounter.recordOpsDurationUnitsConsumed(-5L);
        opsDurationCounter.recordOpsDurationUnitsConsumed(0L);
        opsDurationCounter.recordOpsDurationUnitsConsumed(Long.MIN_VALUE);
        // Negative and zero contributions never decrease or corrupt the running total.
        assertEquals(10L, opsDurationCounter.opsDurationUnitsConsumed());
    }

    @Test
    void accumulationSaturatesInsteadOfOverflowing() {
        final var schedule = mock(OpsDurationSchedule.class);
        final var opsDurationCounter = OpsDurationCounter.withSchedule(schedule);
        opsDurationCounter.recordOpsDurationUnitsConsumed(Long.MAX_VALUE);
        opsDurationCounter.recordOpsDurationUnitsConsumed(Long.MAX_VALUE);
        // Adding beyond Long.MAX_VALUE clamps rather than wrapping to a negative value.
        assertEquals(Long.MAX_VALUE, opsDurationCounter.opsDurationUnitsConsumed());
    }
}
