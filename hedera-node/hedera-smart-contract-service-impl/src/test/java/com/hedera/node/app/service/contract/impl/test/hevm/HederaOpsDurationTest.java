// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.hevm;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_OPS_DURATION_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.service.contract.impl.hevm.HederaOpsDuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HederaOpsDurationTest {
    private HederaOpsDuration hederaOpsDuration;

    @BeforeEach
    void setUp() {
        hederaOpsDuration = new HederaOpsDuration();
    }

    @Test
    void testAllDurationsAreLoadedFromConfig() {
        hederaOpsDuration.applyDurationFromConfig(DEFAULT_OPS_DURATION_CONFIG);

        assertEquals(123, hederaOpsDuration.getOpsDuration()[1]);
        assertEquals(105, hederaOpsDuration.getOpsDuration()[2]);
        assertEquals(2091, hederaOpsDuration.getOpsDuration()[250]);
        assertEquals(566, hederaOpsDuration.opsDurationMultiplier());
        assertEquals(1575, hederaOpsDuration.precompileDurationMultiplier());
        assertEquals(566, hederaOpsDuration.systemContractDurationMultiplier());
    }
}
