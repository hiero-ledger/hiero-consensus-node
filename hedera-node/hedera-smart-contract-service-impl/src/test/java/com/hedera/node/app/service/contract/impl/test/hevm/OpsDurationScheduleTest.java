// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.hevm;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_OPS_DURATION_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.service.contract.impl.hevm.OpsDurationSchedule;
import org.junit.jupiter.api.Test;

class OpsDurationScheduleTest {
    @Test
    void testAllDurationsAreLoadedFromConfig() {
        final var opsDurationSchedule = OpsDurationSchedule.fromConfig(DEFAULT_OPS_DURATION_CONFIG);

        assertEquals(123, opsDurationSchedule.opsDurationByOpCode()[1]);
        assertEquals(105, opsDurationSchedule.opsDurationByOpCode()[2]);
        assertEquals(2091, opsDurationSchedule.opsDurationByOpCode()[250]);
        assertEquals(1575, opsDurationSchedule.opsGasBasedDurationMultiplier());
        assertEquals(1575, opsDurationSchedule.precompileGasBasedDurationMultiplier());
        assertEquals(1575, opsDurationSchedule.systemContractGasBasedDurationMultiplier());
        assertEquals(100, opsDurationSchedule.multipliersDenominator());
    }
}
