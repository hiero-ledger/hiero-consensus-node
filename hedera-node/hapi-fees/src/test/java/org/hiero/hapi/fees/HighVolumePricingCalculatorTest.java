// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.hiero.hapi.support.fees.PiecewiseLinearCurve;
import org.hiero.hapi.support.fees.PiecewiseLinearPoint;
import org.hiero.hapi.support.fees.PricingCurve;
import org.hiero.hapi.support.fees.VariableRateDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HighVolumePricingCalculator}.
 * Tests the fee multiplier calculation based on throttle utilization and pricing curves per HIP-1313.
 */
class HighVolumePricingCalculatorTest {

    private static final int UTILIZATION_SCALE = HighVolumePricingCalculator.UTILIZATION_SCALE; // 100,000
    private static final long MULTIPLIER_SCALE = HighVolumePricingCalculator.MULTIPLIER_SCALE; // 1,000,000

    @Nested
    @DisplayName("Null and edge case handling")
    class NullAndEdgeCases {

        @Test
        @DisplayName("Returns 0 multiplier when variableRateDefinition is null")
        void returnsZeroWhenVariableRateDefinitionIsNull() {
            final long result = HighVolumePricingCalculator.calculateMultiplier(null, 50_000);
            assertEquals(0, result);
        }

        @Test
        @DisplayName("Returns 0 multiplier when utilization is 0")
        void returnsZeroWhenUtilizationIsZero() {
            final var variableRate = createVariableRateWithLinearCurve(4_000_000);
            final long result = HighVolumePricingCalculator.calculateMultiplier(variableRate, 0);
            assertEquals(0, result);
        }

        @Test
        @DisplayName("Clamps negative utilization to 0")
        void clampsNegativeUtilizationToZero() {
            final var variableRate = createVariableRateWithLinearCurve(4_000_000);
            final long result = HighVolumePricingCalculator.calculateMultiplier(variableRate, -1000);
            assertEquals(0, result);
        }

        @Test
        @DisplayName("Clamps utilization above 100% to 100%")
        void clampsUtilizationAbove100Percent() {
            final var variableRate = createVariableRateWithLinearCurve(4_000_000);
            final long result = HighVolumePricingCalculator.calculateMultiplier(variableRate, 150_000);
            // Should be capped at max multiplier
            assertEquals(4_000_000, result);
        }
    }

    @Nested
    @DisplayName("Linear interpolation without pricing curve")
    class LinearInterpolationWithoutCurve {

        @Test
        @DisplayName("Linear interpolation at 0% utilization")
        void linearInterpolationAt0Percent() {
            final var variableRate = createVariableRateWithLinearCurve(4_000_000);
            assertEquals(0, HighVolumePricingCalculator.calculateMultiplier(variableRate, 0));
        }

        @Test
        @DisplayName("Linear interpolation at 25% utilization")
        void linearInterpolationAt25Percent() {
            final var variableRate = createVariableRateWithLinearCurve(4_000_000);
            assertEquals(1_000_000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 25_000));
        }

        @Test
        @DisplayName("Linear interpolation at 50% utilization")
        void linearInterpolationAt50Percent() {
            final var variableRate = createVariableRateWithLinearCurve(4_000_000);
            assertEquals(2_000_000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 50_000));
        }

        @Test
        @DisplayName("Linear interpolation at 75% utilization")
        void linearInterpolationAt75Percent() {
            final var variableRate = createVariableRateWithLinearCurve(4_000_000);
            assertEquals(3_000_000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 75_000));
        }

        @Test
        @DisplayName("Linear interpolation at 100% utilization")
        void linearInterpolationAt100Percent() {
            final var variableRate = createVariableRateWithLinearCurve(4_000_000);
            assertEquals(4_000_000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 100_000));
        }
    }

    @Nested
    @DisplayName("Piecewise linear curve interpolation")
    class PiecewiseLinearCurveInterpolation {

        @Test
        @DisplayName("Interpolates correctly between curve points")
        void interpolatesBetweenCurvePoints() {
            // Create a curve: 0% -> 0, 50% -> 1,000,000, 100% -> 4,000,000
            final var curve = createPiecewiseLinearCurve(
                    point(0, 0),
                    point(50_000, 1_000_000),
                    point(100_000, 4_000_000));
            final var variableRate = createVariableRateWithCurve(4_000_000, curve);

            // At 25% (between 0% and 50%), should interpolate to 500,000
            assertEquals(500_000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 25_000));

            // At 50%, should be exactly 1,000,000
            assertEquals(1_000_000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 50_000));

            // At 75% (between 50% and 100%), should interpolate to 2,500,000
            assertEquals(2_500_000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 75_000));
        }

        @Test
        @DisplayName("Returns first point multiplier when utilization is below first point")
        void returnsFirstPointWhenBelowFirstPoint() {
            // Curve starts at 10% utilization
            final var curve = createPiecewiseLinearCurve(
                    point(10_000, 500_000),
                    point(100_000, 4_000_000));
            final var variableRate = createVariableRateWithCurve(4_000_000, curve);

            // At 5% (below first point), should return first point's multiplier
            assertEquals(500_000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 5_000));
        }

        @Test
        @DisplayName("Returns last point multiplier when utilization is above last point")
        void returnsLastPointWhenAboveLastPoint() {
            // Curve ends at 80% utilization
            final var curve = createPiecewiseLinearCurve(
                    point(0, 0),
                    point(80_000, 3_000_000));
            final var variableRate = createVariableRateWithCurve(4_000_000, curve);

            // At 90% (above last point), should return last point's multiplier
            assertEquals(3_000_000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 90_000));
        }

        @Test
        @DisplayName("Handles single point curve")
        void handlesSinglePointCurve() {
            final var curve = createPiecewiseLinearCurve(point(50_000, 2_000_000));
            final var variableRate = createVariableRateWithCurve(4_000_000, curve);

            // Any utilization should return the single point's multiplier
            assertEquals(2_000_000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 0));
            assertEquals(2_000_000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 50_000));
            assertEquals(2_000_000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 100_000));
        }

        @Test
        @DisplayName("Handles empty curve - returns 0")
        void handlesEmptyCurve() {
            final var curve = createPiecewiseLinearCurve();
            final var variableRate = createVariableRateWithCurve(4_000_000, curve);

            assertEquals(0, HighVolumePricingCalculator.calculateMultiplier(variableRate, 50_000));
        }
    }

    @Nested
    @DisplayName("Max multiplier capping")
    class MaxMultiplierCapping {

        @Test
        @DisplayName("Caps result at max multiplier")
        void capsResultAtMaxMultiplier() {
            // Create a curve that would exceed max multiplier
            final var curve = createPiecewiseLinearCurve(
                    point(0, 0),
                    point(100_000, 10_000_000)); // Would be 11x at 100%
            final var variableRate = createVariableRateWithCurve(4_000_000, curve);

            // Should be capped at max_multiplier (4,000,000)
            assertEquals(4_000_000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 100_000));
        }
    }

    @Nested
    @DisplayName("Effective multiplier conversion")
    class EffectiveMultiplierConversion {

        @Test
        @DisplayName("Converts 0 raw to 1.0x effective")
        void converts0RawTo1xEffective() {
            assertEquals(1.0, HighVolumePricingCalculator.toEffectiveMultiplier(0), 0.0001);
        }

        @Test
        @DisplayName("Converts 1,000,000 raw to 2.0x effective")
        void converts1MRawTo2xEffective() {
            assertEquals(2.0, HighVolumePricingCalculator.toEffectiveMultiplier(1_000_000), 0.0001);
        }

        @Test
        @DisplayName("Converts 2,000,000 raw to 3.0x effective")
        void converts2MRawTo3xEffective() {
            assertEquals(3.0, HighVolumePricingCalculator.toEffectiveMultiplier(2_000_000), 0.0001);
        }

        @Test
        @DisplayName("Converts 4,000,000 raw to 5.0x effective")
        void converts4MRawTo5xEffective() {
            assertEquals(5.0, HighVolumePricingCalculator.toEffectiveMultiplier(4_000_000), 0.0001);
        }

        @Test
        @DisplayName("Converts 500,000 raw to 1.5x effective")
        void converts500kRawTo1_5xEffective() {
            assertEquals(1.5, HighVolumePricingCalculator.toEffectiveMultiplier(500_000), 0.0001);
        }
    }

    @Nested
    @DisplayName("HIP-1313 example scenarios")
    class Hip1313ExampleScenarios {

        @Test
        @DisplayName("Standard HIP-1313 pricing curve: 0% -> 1x, 50% -> 2x, 100% -> 5x")
        void standardHip1313PricingCurve() {
            // This is the example curve from HIP-1313
            final var curve = createPiecewiseLinearCurve(
                    point(0, 0),           // 0% -> 1x (0 raw)
                    point(50_000, 1_000_000),  // 50% -> 2x (1,000,000 raw)
                    point(100_000, 4_000_000)); // 100% -> 5x (4,000,000 raw)
            final var variableRate = createVariableRateWithCurve(4_000_000, curve);

            // Test various utilization levels
            assertEquals(0, HighVolumePricingCalculator.calculateMultiplier(variableRate, 0));
            assertEquals(1_000_000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 50_000));
            assertEquals(4_000_000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 100_000));

            // Verify effective multipliers
            assertEquals(1.0, HighVolumePricingCalculator.toEffectiveMultiplier(0), 0.0001);
            assertEquals(2.0, HighVolumePricingCalculator.toEffectiveMultiplier(1_000_000), 0.0001);
            assertEquals(5.0, HighVolumePricingCalculator.toEffectiveMultiplier(4_000_000), 0.0001);
        }
    }

    // Helper methods

    private static VariableRateDefinition createVariableRateWithLinearCurve(int maxMultiplier) {
        return VariableRateDefinition.newBuilder()
                .maxMultiplier(maxMultiplier)
                .build();
    }

    private static VariableRateDefinition createVariableRateWithCurve(int maxMultiplier, PiecewiseLinearCurve curve) {
        return VariableRateDefinition.newBuilder()
                .maxMultiplier(maxMultiplier)
                .pricingCurve(PricingCurve.newBuilder().piecewiseLinear(curve).build())
                .build();
    }

    private static PiecewiseLinearCurve createPiecewiseLinearCurve(PiecewiseLinearPoint... points) {
        return PiecewiseLinearCurve.newBuilder()
                .points(List.of(points))
                .build();
    }

    private static PiecewiseLinearPoint point(int utilizationPercentage, int multiplier) {
        return PiecewiseLinearPoint.newBuilder()
                .utilizationPercentage(utilizationPercentage)
                .multiplier(multiplier)
                .build();
    }
}

