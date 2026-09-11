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

        assertEquals(123, opsDurationSchedule.opCodeCost(1));
        assertEquals(105, opsDurationSchedule.opCodeCost(2));
        assertEquals(0, opsDurationSchedule.opCodeCost(32)); // KECCAK256
        assertEquals(0, opsDurationSchedule.opCodeCost(55)); // CALLDATACOPY
        assertEquals(0, opsDurationSchedule.opCodeCost(57)); // CODECOPY
        assertEquals(0, opsDurationSchedule.opCodeCost(60)); // EXTCODECOPY
        assertEquals(0, opsDurationSchedule.opCodeCost(62)); // RETURNDATACOPY
        assertEquals(0, opsDurationSchedule.opCodeCost(160)); // LOG0
        assertEquals(0, opsDurationSchedule.opCodeCost(161)); // LOG1
        assertEquals(0, opsDurationSchedule.opCodeCost(162)); // LOG2
        assertEquals(0, opsDurationSchedule.opCodeCost(163)); // LOG3
        assertEquals(0, opsDurationSchedule.opCodeCost(164)); // LOG4
        assertEquals(0, opsDurationSchedule.opCodeCost(240)); // CREATE
        assertEquals(98859, opsDurationSchedule.opCodeCost(241));
        assertEquals(2011, opsDurationSchedule.opCodeCost(242));
        assertEquals(0, opsDurationSchedule.opCodeCost(243)); // RETURN
        assertEquals(1596, opsDurationSchedule.opCodeCost(244));
        assertEquals(0, opsDurationSchedule.opCodeCost(245)); // CREATE2
        assertEquals(2091, opsDurationSchedule.opCodeCost(250));
        assertEquals(0, opsDurationSchedule.opCodeCost(253)); // REVERT

        assertEquals(3332, opsDurationSchedule.accountLazyCreationOpsDurationMultiplier());
        assertEquals(1575, opsDurationSchedule.opsGasBasedDurationMultiplier());
        assertEquals(1575, opsDurationSchedule.precompileGasBasedDurationMultiplier());
        assertEquals(1575, opsDurationSchedule.systemContractGasBasedDurationMultiplier());
        assertEquals(100, opsDurationSchedule.multipliersDenominator());
    }
}
