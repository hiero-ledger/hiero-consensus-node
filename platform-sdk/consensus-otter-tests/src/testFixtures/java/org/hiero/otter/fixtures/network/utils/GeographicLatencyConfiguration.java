// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.utils;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.data.Percentage.withPercentage;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.assertj.core.data.Percentage;

/**
 * Configuration for realistic geographic latency simulation. Defines the distribution of node connections across
 * geographic boundaries and the latency characteristics for each type of connection.
 */
@SuppressWarnings("unused")
public record GeographicLatencyConfiguration(
        @NonNull Percentage sameRegionPercent,
        @NonNull Percentage sameContinentPercent,
        @NonNull LatencyRange sameRegion,
        @NonNull LatencyRange sameContinent,
        @NonNull LatencyRange intercontinental) {

    /**
     * Default configuration with 20% same-region, 40% same-continent, and 40% intercontinental distribution, using
     * standard latency ranges.
     *
     * <table>
     *     <tr><th>Connection Type</th><th>Latency Range</th><th>Jitter</th></tr>
     *     <tr><td>Same-region</td><td>5ms-30ms</td><td>7.5%</td></tr>
     *     <tr><td>Same-continent</td><td>30ms-80ms</td><td>10.0%</td></tr>
     *     <tr><td>Intercontinental</td><td>80ms-300ms</td><td>12.5%</td></tr>
     * </table>
     */
    public static final GeographicLatencyConfiguration DEFAULT = new GeographicLatencyConfiguration(
            withPercentage(20.0),
            withPercentage(40.0),
            LatencyRange.SAME_REGION_DEFAULT,
            LatencyRange.SAME_CONTINENT_DEFAULT,
            LatencyRange.INTERCONTINENTAL_DEFAULT);

    /**
     * Creates a GeographicLatencyConfiguration with specified parameters.
     *
     * @param sameRegionPercent percentage of connections that are same-region (0.0 to 100.0)
     * @param sameContinentPercent percentage of connections that are same-continent (0.0 to 100.0)
     * @param sameRegion latency range for same-region connections
     * @param sameContinent latency range for same-continent connections
     * @param intercontinental latency range for intercontinental connections
     * @throws NullPointerException if any of the parameters are {@code null}
     * @throws IllegalArgumentException if percentages are negative or the total exceeds 100.0
     */
    public GeographicLatencyConfiguration {
        if (sameRegionPercent.value < 0.0) {
            throw new IllegalArgumentException("Same-region percentage must not be negative");
        }
        if (sameContinentPercent.value < 0.0) {
            throw new IllegalArgumentException("Same-continent percentage must not be negative");
        }
        if (sameRegionPercent.value + sameContinentPercent.value > 100.0) {
            throw new IllegalArgumentException("Total percentage cannot exceed 100.0");
        }
        requireNonNull(sameContinent);
        requireNonNull(sameRegion);
        requireNonNull(intercontinental);
    }

    /**
     * Creates a copy of this {@code GeographicLatencyConfiguration} with the specified distribution percentages.
     *
     * @param sameRegionPercent percentage of connections that are same-region (0.0 to 1.0)
     * @param sameContinentPercent percentage of connections that are same-continent (0.0 to 1.0)
     * @return a new {@code GeographicLatencyConfiguration} with the specified distribution
     * @throws NullPointerException if any of the parameters are {@code null}
     * @throws IllegalArgumentException if percentages are negative or the total exceeds 100.0
     */
    @NonNull
    public GeographicLatencyConfiguration withDistribution(
            @NonNull final Percentage sameRegionPercent, @NonNull final Percentage sameContinentPercent) {
        return new GeographicLatencyConfiguration(
                sameRegionPercent, sameContinentPercent, sameRegion, sameContinent, intercontinental);
    }

    /**
     * Creates a copy of this {@code GeographicLatencyConfiguration} with the specified same-region latency.
     *
     * @param range the latency range to use for same-region connections
     * @return a new {@code GeographicLatencyConfiguration} with the specified same-region latency
     * @throws NullPointerException if {@code range} is {@code null}
     */
    @NonNull
    public GeographicLatencyConfiguration withSameRegionLatency(@NonNull final LatencyRange range) {
        return new GeographicLatencyConfiguration(
                sameRegionPercent, sameContinentPercent, range, sameContinent, intercontinental);
    }

    /**
     * Creates a copy of this {@code GeographicLatencyConfiguration} with the specified same-continent latency.
     *
     * @param range the latency range to use for same-continent connections
     * @return a new {@code GeographicLatencyConfiguration} with the specified same-continent latency
     * @throws NullPointerException if {@code range} is {@code null}
     */
    @NonNull
    public GeographicLatencyConfiguration withSameContinentLatency(@NonNull final LatencyRange range) {
        return new GeographicLatencyConfiguration(
                sameRegionPercent, sameContinentPercent, sameRegion, range, intercontinental);
    }

    /**
     * Creates a copy of this {@code GeographicLatencyConfiguration} with the specified intercontinental latency.
     *
     * @param range the latency range to use for intercontinental connections
     * @return a new {@code GeographicLatencyConfiguration} with the specified intercontinental latency
     * @throws NullPointerException if {@code range} is {@code null}
     */
    @NonNull
    public GeographicLatencyConfiguration withIntercontinentalLatency(@NonNull final LatencyRange range) {
        return new GeographicLatencyConfiguration(
                sameRegionPercent, sameContinentPercent, sameRegion, sameContinent, range);
    }
}
