// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_APPEND;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.HOOK_STORE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_AIRDROP;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CLAIM_AIRDROP;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
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
    @Test
    @DisplayName("Exposes the expected set of high-volume functions")
    void hasExpectedHighVolumeFunctions() {
        assertEquals(
                Set.of(
                        CRYPTO_CREATE,
                        CONSENSUS_CREATE_TOPIC,
                        SCHEDULE_CREATE,
                        CRYPTO_APPROVE_ALLOWANCE,
                        FILE_CREATE,
                        FILE_APPEND,
                        CONTRACT_CREATE,
                        HOOK_STORE,
                        TOKEN_ASSOCIATE_TO_ACCOUNT,
                        TOKEN_AIRDROP,
                        TOKEN_CLAIM_AIRDROP,
                        TOKEN_MINT,
                        TOKEN_CREATE),
                HighVolumePricingCalculator.HIGH_VOLUME_PRICING_FUNCTIONS);
        assertEquals(
                Set.of(
                        CRYPTO_CREATE,
                        CONSENSUS_CREATE_TOPIC,
                        SCHEDULE_CREATE,
                        CRYPTO_APPROVE_ALLOWANCE,
                        FILE_CREATE,
                        FILE_APPEND,
                        CONTRACT_CREATE,
                        HOOK_STORE,
                        TOKEN_ASSOCIATE_TO_ACCOUNT,
                        TOKEN_AIRDROP,
                        TOKEN_CLAIM_AIRDROP,
                        TOKEN_MINT,
                        TOKEN_CREATE,
                        CRYPTO_TRANSFER),
                HighVolumePricingCalculator.HIGH_VOLUME_THROTTLE_FUNCTIONS);
    }

    @Test
    @DisplayName("Interpolates linearly between utilization points")
    void linearlyInterpolatesBetweenPoints() {
        assertEquals(3_000L, HighVolumePricingCalculator.linearInterpolate(0, 1_000L, 10_000, 5_000L, 5_000));
    }

    @Test
    @DisplayName("Returns lower multiplier when interpolation range is degenerate")
    void returnsLowerWhenInterpolationRangeIsDegenerate() {
        assertEquals(1_234L, HighVolumePricingCalculator.linearInterpolate(7_000, 1_234L, 7_000, 9_999L, 7_000));
    }

    @Nested
    @DisplayName("Null and edge case handling")
    class NullAndEdgeCases {

        @Test
        @DisplayName("Returns 1000 (1.0x) multiplier when variableRateDefinition is null")
        void returnsLinearInterpolationWhenVariableRateDefinitionIsNull() {
            final long result = HighVolumePricingCalculator.calculateMultiplier(null, 5000);
            assertEquals(1000, result);
        }

        @Test
        @DisplayName("Returns 1000 (1.0x) multiplier when utilization is 0")
        void returns1xWhenUtilizationIsZero() {
            final var variableRate = createVariableRateWithLinearCurve(5000);
            final long result = HighVolumePricingCalculator.calculateMultiplier(variableRate, 0);
            assertEquals(1000, result);
        }

        @Test
        @DisplayName("Clamps negative utilization to 0")
        void clampsNegativeUtilizationToZero() {
            final var variableRate = createVariableRateWithLinearCurve(5000);
            final long result = HighVolumePricingCalculator.calculateMultiplier(variableRate, -1000);
            assertEquals(1000, result);
        }

        @Test
        @DisplayName("Clamps utilization above 100% to 100%")
        void clampsUtilizationAbove100Percent() {
            final var variableRate = createVariableRateWithLinearCurve(5000);
            final long result = HighVolumePricingCalculator.calculateMultiplier(variableRate, 15000);
            // Should be capped at max multiplier
            assertEquals(5000, result);
        }

        @Test
        @DisplayName("Clamps max multiplier below 1000 to 1000")
        void clampsMaxMultiplierBelowMinimum() {
            final var variableRate = createVariableRateWithLinearCurve(500);
            final long result = HighVolumePricingCalculator.calculateMultiplier(variableRate, 10000);
            assertEquals(1000, result);
        }

        @Test
        @DisplayName("Clamps curve point multipliers below 1000 to 1000")
        void clampsCurvePointBelowMinimum() {
            final var curve = createPiecewiseLinearCurve(point(0, 500), point(10000, 1500));
            final var variableRate = createVariableRateWithCurve(5000, curve);
            final long result = HighVolumePricingCalculator.calculateMultiplier(variableRate, 0);
            assertEquals(1000, result);
        }
    }

    @Nested
    @DisplayName("Linear interpolation without pricing curve")
    class LinearInterpolationWithoutCurve {

        @Test
        @DisplayName("Uses linear interpolation when pricing curve exists without piecewise points")
        void linearInterpolationWhenPricingCurvePresentWithoutPiecewiseLinear() {
            final var variableRate = VariableRateDefinition.newBuilder()
                    .maxMultiplier(5000)
                    .pricingCurve(PricingCurve.newBuilder().build())
                    .build();
            assertEquals(3000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 5000));
        }

        @Test
        @DisplayName("Linear interpolation at 0% utilization")
        void linearInterpolationAt0Percent() {
            final var variableRate = createVariableRateWithLinearCurve(5000);
            assertEquals(1000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 0));
        }

        @Test
        @DisplayName("Linear interpolation at 25% utilization")
        void linearInterpolationAt25Percent() {
            final var variableRate = createVariableRateWithLinearCurve(5000);
            assertEquals(2000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 2500));
        }

        @Test
        @DisplayName("Linear interpolation at 50% utilization")
        void linearInterpolationAt50Percent() {
            final var variableRate = createVariableRateWithLinearCurve(5000);
            assertEquals(3000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 5000));
        }

        @Test
        @DisplayName("Linear interpolation at 75% utilization")
        void linearInterpolationAt75Percent() {
            final var variableRate = createVariableRateWithLinearCurve(5000);
            assertEquals(4000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 7500));
        }

        @Test
        @DisplayName("Linear interpolation at 100% utilization")
        void linearInterpolationAt100Percent() {
            final var variableRate = createVariableRateWithLinearCurve(5000);
            assertEquals(5000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 10000));
        }
    }

    @Nested
    @DisplayName("Piecewise linear curve interpolation")
    class PiecewiseLinearCurveInterpolation {

        @Test
        @DisplayName("Interpolates correctly between curve points")
        void interpolatesBetweenCurvePoints() {
            // Create a curve: 0% -> 1.0x, 50% -> 2.0x, 100% -> 5.0x
            final var curve = createPiecewiseLinearCurve(point(0, 1000), point(5000, 2000), point(10000, 5000));
            final var variableRate = createVariableRateWithCurve(5000, curve);

            // At 25% (between 0% and 50%), should interpolate to 1.5x (1,500)
            assertEquals(1500, HighVolumePricingCalculator.calculateMultiplier(variableRate, 2500));

            // At 50%, should be exactly 2.0x (2,000)
            assertEquals(2000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 5000));

            // At 75% (between 50% and 100%), should interpolate to 3.5x (3,500)
            assertEquals(3500, HighVolumePricingCalculator.calculateMultiplier(variableRate, 7500));
        }

        @Test
        @DisplayName("Returns first point multiplier when utilization is below first point")
        void returnsFirstPointWhenBelowFirstPoint() {
            // Curve starts at 10% utilization
            final var curve = createPiecewiseLinearCurve(point(1000, 1500), point(10000, 5000));
            final var variableRate = createVariableRateWithCurve(5000, curve);

            // At 5% (below first point), should return first point's multiplier
            assertEquals(1500, HighVolumePricingCalculator.calculateMultiplier(variableRate, 500));
        }

        @Test
        @DisplayName("Returns 4x at 0% utilization when first point is 2 bps (CryptoCreate floor)")
        void returnsCryptoCreateFloorAtZeroUtilization() {
            // Curve starts at 2 basis points with a 4x multiplier
            final var curve = createPiecewiseLinearCurve(point(2, 4000), point(10000, 5000));
            final var variableRate = createVariableRateWithCurve(200000, curve);

            // At 0% utilization, should return the first point's multiplier (4x)
            assertEquals(4000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 0));
        }

        @Test
        @DisplayName("Returns 4x below first point (1 bps) when first point is 2 bps")
        void returnsCryptoCreateFloorBelowFirstPoint() {
            final var curve = createPiecewiseLinearCurve(point(2, 4000), point(10000, 5000));
            final var variableRate = createVariableRateWithCurve(200000, curve);

            assertEquals(4000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 1));
        }

        @Test
        @DisplayName("Returns 4x at first point (2 bps) when first point is 2 bps")
        void returnsCryptoCreateFloorAtFirstPoint() {
            final var curve = createPiecewiseLinearCurve(point(2, 4000), point(10000, 5000));
            final var variableRate = createVariableRateWithCurve(200000, curve);

            assertEquals(4000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 2));
        }

        @Test
        @DisplayName("Returns last point multiplier when utilization is above last point")
        void returnsLastPointWhenAboveLastPoint() {
            // Curve ends at 80% utilization
            final var curve = createPiecewiseLinearCurve(point(0, 1000), point(8000, 4000));
            final var variableRate = createVariableRateWithCurve(5000, curve);

            // At 90% (above last point), should return last point's multiplier
            assertEquals(4000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 9000));
        }

        @Test
        @DisplayName("Handles single point curve")
        void handlesSinglePointCurve() {
            final var curve = createPiecewiseLinearCurve(point(5000, 2000));
            final var variableRate = createVariableRateWithCurve(5000, curve);

            // Any utilization should return the single point's multiplier
            assertEquals(2000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 0));
            assertEquals(2000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 5000));
            assertEquals(2000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 10000));
        }

        @Test
        @DisplayName("Handles empty curve - returns linear interpolation)")
        void handlesEmptyCurve() {
            final var curve = createPiecewiseLinearCurve();
            final var variableRate = createVariableRateWithCurve(5000, curve);

            assertEquals(3000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 5000));
        }
    }

    @Nested
    @DisplayName("Max multiplier capping")
    class MaxMultiplierCapping {

        @Test
        @DisplayName("Caps result at max multiplier")
        void capsResultAtMaxMultiplier() {
            // Create a curve that would exceed max multiplier
            final var curve = createPiecewiseLinearCurve(point(0, 1000), point(10000, 10000)); // Would be 10x at 100%
            final var variableRate = createVariableRateWithCurve(5000, curve);

            // Should be capped at maxmultiplier (5,000 = 5.0x)
            assertEquals(5000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 10000));
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
                    point(0, 1000), // 0% -> 1x (1,000 raw)
                    point(5000, 2000), // 50% -> 2x (2,000 raw)
                    point(10000, 5000)); // 100% -> 5x (5,000 raw)
            final var variableRate = createVariableRateWithCurve(5000, curve);

            // Test various utilization levels
            assertEquals(1000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 0));
            assertEquals(2000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 5000));
            assertEquals(5000, HighVolumePricingCalculator.calculateMultiplier(variableRate, 10000));
        }
    }

    // Helper methods

    private static VariableRateDefinition createVariableRateWithLinearCurve(int maxMultiplier) {
        return VariableRateDefinition.newBuilder().maxMultiplier(maxMultiplier).build();
    }

    private static VariableRateDefinition createVariableRateWithCurve(int maxMultiplier, PiecewiseLinearCurve curve) {
        return VariableRateDefinition.newBuilder()
                .maxMultiplier(maxMultiplier)
                .pricingCurve(PricingCurve.newBuilder().piecewiseLinear(curve).build())
                .build();
    }

    private static PiecewiseLinearCurve createPiecewiseLinearCurve(PiecewiseLinearPoint... points) {
        return PiecewiseLinearCurve.newBuilder().points(List.of(points)).build();
    }

    private static PiecewiseLinearPoint point(int utilizationBasisPoints, int multiplier) {
        return PiecewiseLinearPoint.newBuilder()
                .utilizationBasisPoints(utilizationBasisPoints)
                .multiplier(multiplier)
                .build();
    }
}
