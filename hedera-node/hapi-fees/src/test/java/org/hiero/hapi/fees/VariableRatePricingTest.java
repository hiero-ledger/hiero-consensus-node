// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.hiero.hapi.support.fees.PiecewiseLinearCurve;
import org.hiero.hapi.support.fees.PiecewiseLinearPoint;
import org.hiero.hapi.support.fees.PricingCurve;
import org.hiero.hapi.support.fees.VariableRateDefinition;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VariableRatePricing}.
 */
class VariableRatePricingTest {

    @Test
    void calculateMultiplierReturnsOneWhenDefinitionIsNull() {
        assertEquals(1.0, VariableRatePricing.calculateMultiplier(null, 50000));
    }

    @Test
    void calculateMultiplierLinearlyInterpolatesWhenNoCurve() {
        // max_multiplier of 4,000,000 means effective max = 1 + 4 = 5.0
        final var definition = VariableRateDefinition.newBuilder()
                .maxMultiplier(4_000_000)
                .build();

        // At 0% utilization, multiplier should be 1.0
        assertEquals(1.0, VariableRatePricing.calculateMultiplier(definition, 0), 0.001);

        // At 50% utilization (50000 in thousandths), multiplier should be 3.0 (midpoint between 1 and 5)
        assertEquals(3.0, VariableRatePricing.calculateMultiplier(definition, 50000), 0.001);

        // At 100% utilization (100000 in thousandths), multiplier should be 5.0
        assertEquals(5.0, VariableRatePricing.calculateMultiplier(definition, 100000), 0.001);
    }

    @Test
    void calculateMultiplierUsesPiecewiseLinearCurve() {
        // Create a curve with points: (0%, 1.0), (50%, 2.0), (100%, 5.0)
        // Note: multiplier values are (actual - 1) * 1,000,000
        final var points = List.of(
                PiecewiseLinearPoint.newBuilder()
                        .utilizationPercentage(0)
                        .multiplier(0) // 1.0
                        .build(),
                PiecewiseLinearPoint.newBuilder()
                        .utilizationPercentage(50000) // 50%
                        .multiplier(1_000_000) // 2.0
                        .build(),
                PiecewiseLinearPoint.newBuilder()
                        .utilizationPercentage(100000) // 100%
                        .multiplier(4_000_000) // 5.0
                        .build());

        final var curve = PiecewiseLinearCurve.newBuilder().points(points).build();
        final var pricingCurve = PricingCurve.newBuilder().piecewiseLinear(curve).build();
        final var definition = VariableRateDefinition.newBuilder()
                .maxMultiplier(10_000_000) // max = 11.0 (won't be reached)
                .pricingCurve(pricingCurve)
                .build();

        // At 0% utilization
        assertEquals(1.0, VariableRatePricing.calculateMultiplier(definition, 0), 0.001);

        // At 25% utilization (25000), interpolate between (0%, 1.0) and (50%, 2.0)
        assertEquals(1.5, VariableRatePricing.calculateMultiplier(definition, 25000), 0.001);

        // At 50% utilization
        assertEquals(2.0, VariableRatePricing.calculateMultiplier(definition, 50000), 0.001);

        // At 75% utilization (75000), interpolate between (50%, 2.0) and (100%, 5.0)
        assertEquals(3.5, VariableRatePricing.calculateMultiplier(definition, 75000), 0.001);

        // At 100% utilization
        assertEquals(5.0, VariableRatePricing.calculateMultiplier(definition, 100000), 0.001);
    }

    @Test
    void calculateMultiplierCapsAtMaxMultiplier() {
        // max_multiplier of 1,000,000 means effective max = 1 + 1 = 2.0
        // But curve goes up to 5.0
        final var points = List.of(
                PiecewiseLinearPoint.newBuilder()
                        .utilizationPercentage(0)
                        .multiplier(0) // 1.0
                        .build(),
                PiecewiseLinearPoint.newBuilder()
                        .utilizationPercentage(100000) // 100%
                        .multiplier(4_000_000) // 5.0
                        .build());

        final var curve = PiecewiseLinearCurve.newBuilder().points(points).build();
        final var pricingCurve = PricingCurve.newBuilder().piecewiseLinear(curve).build();
        final var definition = VariableRateDefinition.newBuilder()
                .maxMultiplier(1_000_000) // max = 2.0
                .pricingCurve(pricingCurve)
                .build();

        // At 100% utilization, curve says 5.0 but max is 2.0
        assertEquals(2.0, VariableRatePricing.calculateMultiplier(definition, 100000), 0.001);
    }

    @Test
    void interpolatePiecewiseLinearHandlesEmptyPoints() {
        final var curve = PiecewiseLinearCurve.newBuilder().points(List.of()).build();
        assertEquals(1.0, VariableRatePricing.interpolatePiecewiseLinear(curve, 50000), 0.001);
    }

    @Test
    void interpolatePiecewiseLinearHandlesSinglePoint() {
        final var points = List.of(PiecewiseLinearPoint.newBuilder()
                .utilizationPercentage(50000)
                .multiplier(2_000_000) // 3.0
                .build());
        final var curve = PiecewiseLinearCurve.newBuilder().points(points).build();

        // Single point - always returns that multiplier
        assertEquals(3.0, VariableRatePricing.interpolatePiecewiseLinear(curve, 0), 0.001);
        assertEquals(3.0, VariableRatePricing.interpolatePiecewiseLinear(curve, 50000), 0.001);
        assertEquals(3.0, VariableRatePricing.interpolatePiecewiseLinear(curve, 100000), 0.001);
    }

    @Test
    void applyMultiplierReturnsBaseFeeWhenMultiplierIsOne() {
        assertEquals(1000L, VariableRatePricing.applyMultiplier(1000L, 1.0));
        assertEquals(1000L, VariableRatePricing.applyMultiplier(1000L, 0.5)); // Less than 1 treated as 1
    }

    @Test
    void applyMultiplierScalesFee() {
        assertEquals(2000L, VariableRatePricing.applyMultiplier(1000L, 2.0));
        assertEquals(3500L, VariableRatePricing.applyMultiplier(1000L, 3.5));
    }

    @Test
    void applyMultiplierClampsToMaxLong() {
        assertEquals(Long.MAX_VALUE, VariableRatePricing.applyMultiplier(Long.MAX_VALUE, 2.0));
    }
}

