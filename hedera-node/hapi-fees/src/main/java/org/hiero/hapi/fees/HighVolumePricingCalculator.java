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
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Set;
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
 *   <li>multiplier = 1,000 means a multiplier of 1x (1,000/1,000)</li>
 *   <li>multiplier = 2,000 means a multiplier of 2x (2,000/1,000)</li>
 *   <li>multiplier = 5,000 means a multiplier of 5x (5,000/1,000)</li>
 * </ul>
 */
public final class HighVolumePricingCalculator {

    /** The scale factor for utilization percentage (10,000 = 100%). */
    public static final int UTILIZATION_SCALE = 10_000;

    /** The scale factor for multiplier values (1,000 = 1x). */
    public static final long MULTIPLIER_SCALE = 1_000L;

    public static final Set<HederaFunctionality> HIGH_VOLUME_FUNCTIONS = Set.of(
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
            CRYPTO_TRANSFER);

    private HighVolumePricingCalculator() {
        // Utility class
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

        final int maxMultiplier = Math.max(variableRateDefinition.maxMultiplier(), (int) MULTIPLIER_SCALE);
        final PricingCurve pricingCurve = variableRateDefinition.pricingCurve();

        // Clamp utilization to valid range
        final int clampedUtilization = Math.max(0, Math.min(utilizationBasisPoints, UTILIZATION_SCALE));

        long rawMultiplier;
        if (pricingCurve == null
                || !pricingCurve.hasPiecewiseLinear()
                || pricingCurve
                        .piecewiseLinearOrElse(PiecewiseLinearCurve.DEFAULT)
                        .points()
                        .isEmpty()) {
            // No pricing curve specified - use linear interpolation between 1x (1000) and max_multiplier
            rawMultiplier =
                    linearInterpolate(0, (int) MULTIPLIER_SCALE, UTILIZATION_SCALE, maxMultiplier, clampedUtilization);
        } else {
            // Use piecewise linear curve
            rawMultiplier =
                    interpolatePiecewiseLinear(requireNonNull(pricingCurve.piecewiseLinear()), clampedUtilization);
        }

        // Cap at max multiplier, enforce minimum multiplier
        return Math.max(MULTIPLIER_SCALE, Math.min(rawMultiplier, maxMultiplier));
    }

    /**
     * Interpolates the multiplier from a piecewise linear curve based on utilization.
     *
     * @param curve the piecewise linear curve
     * @param utilizationBasisPoints the utilization percentage (0 to 10,000)
     * @return the interpolated multiplier (scaled)
     */
    public static long interpolatePiecewiseLinear(
            @NonNull final PiecewiseLinearCurve curve, final int utilizationBasisPoints) {
        final List<PiecewiseLinearPoint> points = curve.points();
        // If there is only one point, return that point's multiplier
        if (points.size() == 1) {
            return normalizeMultiplier(points.getFirst().multiplier());
        }

        // Find the two points to interpolate between
        PiecewiseLinearPoint lowerPoint = null;
        PiecewiseLinearPoint upperPoint = null;

        for (final PiecewiseLinearPoint point : points) {
            if (point.utilizationBasisPoints() <= utilizationBasisPoints) {
                lowerPoint = point;
            }
            if (point.utilizationBasisPoints() >= utilizationBasisPoints && upperPoint == null) {
                upperPoint = point;
            }
        }

        // If utilization is below the first point, use the first point's multiplier
        if (lowerPoint == null) {
            return normalizeMultiplier(points.getFirst().multiplier());
        }

        // If utilization is above the last point, use the last point's multiplier
        if (upperPoint == null) {
            return normalizeMultiplier(points.getLast().multiplier());
        }

        // If we're exactly on a point, return that point's multiplier
        if (lowerPoint.utilizationBasisPoints() == upperPoint.utilizationBasisPoints()) {
            return normalizeMultiplier(upperPoint.multiplier());
        }

        // Interpolate between the two points
        return linearInterpolate(
                lowerPoint.utilizationBasisPoints(),
                normalizeMultiplier(lowerPoint.multiplier()),
                upperPoint.utilizationBasisPoints(),
                normalizeMultiplier(upperPoint.multiplier()),
                utilizationBasisPoints);
    }

    /**
     * Performs linear interpolation between two points.
     */
    public static long linearInterpolate(
            final int lowerUtilization,
            final long lowerMultiplier,
            final int upperUtilization,
            final long upperMultiplier,
            final int utilization) {
        if (upperUtilization == lowerUtilization) {
            return lowerMultiplier;
        }
        // Use long arithmetic to avoid overflow
        final long utilizationDiff = upperUtilization - lowerUtilization;
        final long multiplierDiff = upperMultiplier - lowerMultiplier;
        final long offset = utilization - lowerUtilization;
        return lowerMultiplier + (multiplierDiff * offset) / utilizationDiff;
    }

    /**
     * Enforces the minimum multiplier of 1x (1,000 scaled).
     */
    private static long normalizeMultiplier(final long multiplier) {
        return Math.max(multiplier, MULTIPLIER_SCALE);
    }
}
