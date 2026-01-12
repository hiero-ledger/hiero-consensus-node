// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.hiero.hapi.support.fees.PiecewiseLinearCurve;
import org.hiero.hapi.support.fees.PiecewiseLinearPoint;
import org.hiero.hapi.support.fees.VariableRateDefinition;

/**
 * Utility class for calculating variable rate pricing multipliers based on throttle utilization.
 *
 * <p>Per HIP-1313, high-volume transactions use variable rate pricing where the fee multiplier
 * increases with throttle utilization. The multiplier is calculated from a pricing curve
 * (typically piecewise linear) that maps utilization percentage to a fee multiplier.
 */
public final class VariableRatePricing {

    /** The divisor used to convert raw multiplier values to floating point (1,000,000). */
    public static final double MULTIPLIER_DIVISOR = 1_000_000.0;

    /** The divisor used to convert utilization percentage from thousandths to percent (1,000). */
    public static final double UTILIZATION_DIVISOR = 1_000.0;

    private VariableRatePricing() {
        // Utility class - no instantiation
    }

    /**
     * Calculates the fee multiplier for a given utilization percentage using the variable rate definition.
     *
     * <p>The multiplier is calculated as follows:
     * <ol>
     *   <li>If no pricing curve is specified, linearly interpolate between 1.0 and max_multiplier</li>
     *   <li>If a piecewise linear curve is specified, interpolate between the curve points</li>
     *   <li>The result is capped at max_multiplier</li>
     * </ol>
     *
     * @param variableRateDef the variable rate definition containing max_multiplier and pricing_curve
     * @param utilizationScaled the current utilization in thousandths of one percent (0-100000)
     * @return the fee multiplier (1.0 to max_multiplier)
     */
    public static double calculateMultiplier(@Nullable final VariableRateDefinition variableRateDef, final int utilizationScaled) {
        if (variableRateDef == null) {
            return 1.0;
        }

        // Convert max_multiplier from scaled integer to floating point
        // max_multiplier is divided by 1,000,000 and added to 1
        final double maxMultiplier = 1.0 + (variableRateDef.maxMultiplier() / MULTIPLIER_DIVISOR);

        // Convert utilization from thousandths of one percent (0-100000) to fraction (0.0-1.0)
        final double utilizationFraction = utilizationScaled / (100.0 * UTILIZATION_DIVISOR);

        final var pricingCurve = variableRateDef.pricingCurve();
        double multiplier;

        if (pricingCurve == null || !pricingCurve.hasPiecewiseLinear()) {
            // No pricing curve specified - linearly interpolate between 1.0 and max_multiplier
            multiplier = 1.0 + (utilizationFraction * (maxMultiplier - 1.0));
        } else {
            // Use piecewise linear curve
            multiplier = interpolatePiecewiseLinear(pricingCurve.piecewiseLinearOrThrow(), utilizationScaled);
        }

        // Cap at max_multiplier
        return Math.min(multiplier, maxMultiplier);
    }

    /**
     * Interpolates the fee multiplier from a piecewise linear curve.
     *
     * <p>Given a utilization percentage, finds the two surrounding points in the curve
     * and linearly interpolates between them.
     *
     * @param curve the piecewise linear curve
     * @param utilizationScaled the current utilization in thousandths of one percent (0-100000)
     * @return the interpolated multiplier
     */
    public static double interpolatePiecewiseLinear(@NonNull final PiecewiseLinearCurve curve, final int utilizationScaled) {
        final List<PiecewiseLinearPoint> points = curve.points();

        if (points == null || points.isEmpty()) {
            // No points defined - return base multiplier
            return 1.0;
        }

        if (points.size() == 1) {
            // Single point - return its multiplier
            return 1.0 + (points.getFirst().multiplier() / MULTIPLIER_DIVISOR);
        }

        // Find the two points that bracket the utilization
        PiecewiseLinearPoint lowerPoint = null;
        PiecewiseLinearPoint upperPoint = null;

        for (final var point : points) {
            if (point.utilizationPercentage() <= utilizationScaled) {
                lowerPoint = point;
            }
            if (point.utilizationPercentage() >= utilizationScaled && upperPoint == null) {
                upperPoint = point;
            }
        }

        // Handle edge cases
        if (lowerPoint == null) {
            // Utilization is below the first point - use first point's multiplier
            return 1.0 + (points.getFirst().multiplier() / MULTIPLIER_DIVISOR);
        }
        if (upperPoint == null) {
            // Utilization is above the last point - use last point's multiplier
            return 1.0 + (points.getLast().multiplier() / MULTIPLIER_DIVISOR);
        }
        if (lowerPoint.utilizationPercentage() == upperPoint.utilizationPercentage()) {
            // Exact match or step function - use the upper point's multiplier
            return 1.0 + (upperPoint.multiplier() / MULTIPLIER_DIVISOR);
        }

        // Linear interpolation between the two points
        final double lowerUtil = lowerPoint.utilizationPercentage();
        final double upperUtil = upperPoint.utilizationPercentage();
        final double lowerMult = 1.0 + (lowerPoint.multiplier() / MULTIPLIER_DIVISOR);
        final double upperMult = 1.0 + (upperPoint.multiplier() / MULTIPLIER_DIVISOR);

        final double fraction = (utilizationScaled - lowerUtil) / (upperUtil - lowerUtil);
        return lowerMult + (fraction * (upperMult - lowerMult));
    }

    /**
     * Applies the variable rate multiplier to a base fee.
     *
     * @param baseFee the base fee in tinycents
     * @param multiplier the fee multiplier (1.0 or greater)
     * @return the adjusted fee in tinycents
     */
    public static long applyMultiplier(final long baseFee, final double multiplier) {
        if (multiplier <= 1.0) {
            return baseFee;
        }
        final double adjustedFee = baseFee * multiplier;
        // Clamp to Long.MAX_VALUE to prevent overflow
        if (adjustedFee > Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.round(adjustedFee);
    }
}

