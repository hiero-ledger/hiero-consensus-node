// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.hiero.hapi.support.fees.PiecewiseLinearCurve;
import org.hiero.hapi.support.fees.PiecewiseLinearPoint;
import org.hiero.hapi.support.fees.PricingCurve;
import org.hiero.hapi.support.fees.VariableRateDefinition;

/**
 * Calculates the fee multiplier for high-volume transactions based on throttle utilization
 * and the pricing curve defined in the fee schedule per HIP-1313.
 *
 * <p>The multiplier is calculated using piecewise linear interpolation between points
 * on the pricing curve. The utilization percentage is expressed in hundredths of one percent
 * (basis points, 0 to 10,000), and the multiplier values are expressed as values that are
 * divided by 1,000.
 *
 * <p>For example:
 * <ul>
 *   <li>utilizationBasisPoints = 5,000 means 50% utilization</li>
 *   <li>multiplier = 1,000 means a multiplier of 1.0x (1,000/1,000)</li>
 *   <li>multiplier = 2,000 means a multiplier of 2.0x (2,000/1,000)</li>
 *   <li>multiplier = 5,000 means a multiplier of 5.0x (5,000/1,000)</li>
 * </ul>
 */
public final class HighVolumePricingCalculator {

    /** The scale factor for utilization percentage (10,000 = 100%). */
    public static final int UTILIZATION_SCALE = 10_000;

    /** The scale factor for multiplier values (1,000 = 1.0x). */
    public static final long MULTIPLIER_SCALE = 1_000L;

    private HighVolumePricingCalculator() {
        // Utility class - prevent instantiation
    }

    /**
     * Calculates the fee multiplier for a high-volume transaction based on the current
     * throttle utilization and the variable rate definition from the fee schedule.
     *
     * @param variableRateDefinition the variable rate definition containing max multiplier and pricing curve
     * @param utilizationBasisPoints the current utilization percentage in hundredths of one percent (0 to 10,000)
     * @return the calculated multiplier as a long value (scaled by MULTIPLIER_SCALE)
     */
    public static long calculateMultiplier(
            @Nullable final VariableRateDefinition variableRateDefinition, final int utilizationBasisPoints) {
        // If no variable rate definition, return base multiplier (1x = 1000 in scaled form)
        if (variableRateDefinition == null) {
            return MULTIPLIER_SCALE;
        }

        final int maxMultiplier = variableRateDefinition.maxMultiplier();
        final PricingCurve pricingCurve = variableRateDefinition.pricingCurve();

        // Clamp utilization to valid range
        final int clampedUtilization = Math.max(0, Math.min(utilizationBasisPoints, UTILIZATION_SCALE));

        long rawMultiplier;
        if (pricingCurve == null || !pricingCurve.hasPiecewiseLinear()) {
            // No pricing curve specified - use linear interpolation between 1x (1000) and max_multiplier
            rawMultiplier = linearInterpolate(0, (int) MULTIPLIER_SCALE, UTILIZATION_SCALE, maxMultiplier, clampedUtilization);
        } else {
            // Use piecewise linear curve
            rawMultiplier = interpolatePiecewiseLinear(pricingCurve.piecewiseLinear(), clampedUtilization);
        }

        // Cap at max multiplier
        return Math.min(rawMultiplier, maxMultiplier);
    }

    /**
     * Calculates the effective multiplier as a double value (for display/logging purposes).
     * The effective multiplier is the raw multiplier divided by MULTIPLIER_SCALE.
     *
     * @param rawMultiplier the raw multiplier value (scaled by MULTIPLIER_SCALE)
     * @return the effective multiplier as a double (e.g., 2.0 for 2x)
     */
    public static double toEffectiveMultiplier(final long rawMultiplier) {
        return (double) rawMultiplier / MULTIPLIER_SCALE;
    }

    /**
     * Interpolates the multiplier from a piecewise linear curve based on utilization.
     *
     * @param curve the piecewise linear curve
     * @param utilizationBasisPoints the utilization percentage (0 to 10,000)
     * @return the interpolated multiplier (scaled)
     */
    private static long interpolatePiecewiseLinear(
            @NonNull final PiecewiseLinearCurve curve, final int utilizationBasisPoints) {
        final List<PiecewiseLinearPoint> points = curve.points();

        // Handle empty curve - return base multiplier (1x = 1000)
        if (points.isEmpty()) {
            return MULTIPLIER_SCALE;
        }

        // Handle single point - return that point's multiplier
        if (points.size() == 1) {
            return points.get(0).multiplier();
        }

        // Find the two points to interpolate between
        PiecewiseLinearPoint lowerPoint = null;
        PiecewiseLinearPoint upperPoint = null;

        for (int i = 0; i < points.size(); i++) {
            final PiecewiseLinearPoint point = points.get(i);
            if (point.utilizationBasisPoints() <= utilizationBasisPoints) {
                lowerPoint = point;
            }
            if (point.utilizationBasisPoints() >= utilizationBasisPoints && upperPoint == null) {
                upperPoint = point;
            }
        }

        // If utilization is below the first point, use the first point's multiplier
        if (lowerPoint == null) {
            return points.get(0).multiplier();
        }

        // If utilization is above the last point, use the last point's multiplier
        if (upperPoint == null) {
            return points.get(points.size() - 1).multiplier();
        }

        // If we're exactly on a point, return that point's multiplier
        if (lowerPoint.utilizationBasisPoints() == upperPoint.utilizationBasisPoints()) {
            return upperPoint.multiplier();
        }

        // Interpolate between the two points
        return linearInterpolate(
                lowerPoint.utilizationBasisPoints(),
                lowerPoint.multiplier(),
                upperPoint.utilizationBasisPoints(),
                upperPoint.multiplier(),
                utilizationBasisPoints);
    }

    /**
     * Performs linear interpolation between two points.
     */
    private static long linearInterpolate(final int x1, final long y1, final int x2, final long y2, final int x) {
        if (x2 == x1) {
            return y1;
        }
        // Use long arithmetic to avoid overflow
        final long dx = x2 - x1;
        final long dy = y2 - y1;
        final long offset = x - x1;
        return y1 + (dy * offset) / dx;
    }
}
