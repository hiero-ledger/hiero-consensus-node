// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.hevm;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_OPS_DURATION_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.service.contract.impl.hevm.OpsDurationSchedule;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.gascalculator.CancunGasCalculator;
import org.hyperledger.besu.evm.gascalculator.PragueGasCalculator;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for overflow-safe ops-duration accounting, at the arithmetic layer.
 *
 * <p>Some EVM precompiles (for example MODEXP with maximal length fields) report a gas requirement that
 * Besu saturates to {@link Long#MAX_VALUE}. The earlier {@code gasRequirement * multiplier / denominator}
 * accounting used raw 64-bit arithmetic that could overflow. This test pins the saturation behavior as an
 * environmental canary against the real Besu gas calculators, and verifies that the production
 * {@link OpsDurationSchedule#gasBasedOpsDuration(long, long)} helper computes the cost in an overflow-safe,
 * non-negative way.
 *
 * <p>The behavioral guarantee — ops duration is recorded only for calls that can afford their gas
 * requirement, so a call whose quote saturates to {@code Long.MAX_VALUE} (which no transaction can afford)
 * records nothing — is covered by {@code CustomMessageCallProcessorTest} (unit) and
 * {@code OpsDurationPrecompileFeedTest} (end-to-end).
 */
class OpsDurationOverflowRegressionTest {

    // A 96-byte MODEXP header whose declared lengths drive Besu's gas quote to its Long.MAX_VALUE ceiling.
    private static final Bytes MODEXP_HEADER =
            Bytes.fromHexString("0x0000000000000000000000000000000000000000000000007ffffffffffffff8"
                    + "0000000000000000000000000000000000000000000000000000000000000001"
                    + "0000000000000000000000000000000000000000000000007ffffffffffffff8");

    @Test
    void besuModExpQuoteStillSaturatesToLongMax() {
        assertEquals(96, MODEXP_HEADER.size());
        // Prague is the active fork; Cancun is checked for parity. Both must saturate.
        assertEquals(
                Long.MAX_VALUE,
                new PragueGasCalculator().modExpGasCost(MODEXP_HEADER),
                "active-fork MODEXP quote must saturate to Long.MAX_VALUE");
        assertEquals(Long.MAX_VALUE, new CancunGasCalculator().modExpGasCost(MODEXP_HEADER));
    }

    @Test
    void gasBasedOpsDurationIsOverflowSafeForSaturatedQuote() {
        final OpsDurationSchedule schedule = OpsDurationSchedule.fromConfig(DEFAULT_OPS_DURATION_CONFIG);
        assertEquals(1575L, schedule.precompileGasBasedDurationMultiplier());
        assertEquals(100L, schedule.multipliersDenominator());

        // Old, unguarded arithmetic: Long.MAX_VALUE * 1575 / 100 wrapped a signed 64-bit long to a huge
        // (and for other magnitudes, negative) value. The production helper saturates the product instead.
        final long opsDurationCost =
                schedule.gasBasedOpsDuration(Long.MAX_VALUE, schedule.precompileGasBasedDurationMultiplier());
        assertTrue(opsDurationCost > 0L, "ops duration must never wrap to a non-positive value");
        assertEquals(Long.MAX_VALUE / 100L, opsDurationCost, "saturated product divided by the denominator");
    }

    @Test
    void gasBasedOpsDurationTreatsNonPositiveInputsAsZero() {
        final OpsDurationSchedule schedule = OpsDurationSchedule.fromConfig(DEFAULT_OPS_DURATION_CONFIG);
        assertEquals(0L, schedule.gasBasedOpsDuration(0L, 1575L));
        assertEquals(0L, schedule.gasBasedOpsDuration(-1L, 1575L));
        assertEquals(0L, schedule.gasBasedOpsDuration(Long.MAX_VALUE, 0L));
    }
}
