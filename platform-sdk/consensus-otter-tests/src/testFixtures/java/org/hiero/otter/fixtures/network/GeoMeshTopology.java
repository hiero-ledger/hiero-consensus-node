// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.data.Percentage.withPercentage;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.assertj.core.data.Percentage;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Node;

/**
 * Interface for a mesh network topology that simulates realistic latency and jitter based on geographic distribution.
 */
@SuppressWarnings("unused")
public interface GeoMeshTopology extends MeshTopology {

    /**
     * Adds a single node to the network in the specified geographic location.
     *
     * @param continent the continent for the new node
     * @param region the region within the continent for the new node
     * @return the created node
     * @throws NullPointerException if {@code continent} or {@code region} is {@code null}
     */
    @NonNull
    default Node addNode(@NonNull final String continent, @NonNull final String region) {
        return addNodes(1, continent, region).getFirst();
    }

    /**
     * Adds multiple nodes to the network in the specified geographic location.
     *
     * @param count the number of nodes to add
     * @param continent the continent for the new nodes
     * @param region the region within the continent for the new nodes
     * @return list of created nodes
     * @throws NullPointerException if {@code continent} or {@code region} is {@code null}
     */
    @NonNull
    List<Node> addNodes(int count, @NonNull String continent, @NonNull String region);

    /**
     * Add an instrumented node to the topology.
     *
     * <p>This method is used to add a node that has additional instrumentation for testing purposes.
     * For example, it can exhibit malicious or erroneous behavior.
     *
     * @param continent the continent for the new node
     * @param region the region within the continent for the new node
     * @return the added instrumented node
     * @throws NullPointerException if {@code continent} or {@code region} is {@code null}
     */
    @NonNull
    InstrumentedNode addInstrumentedNode(@NonNull final String continent, @NonNull final String region);

    /**
     * Gets the continent of the specified node.
     *
     * @param node the node to query
     * @return the continent of the node
     * @throws NullPointerException if {@code node} is {@code null}
     * @throws IllegalArgumentException if the node is not part of this topology
     */
    @NonNull
    String getContinent(@NonNull Node node);

    /**
     * Gets the region of the specified node.
     *
     * @param node the node to query
     * @return the region of the node
     * @throws NullPointerException if {@code node} is {@code null}
     * @throws IllegalArgumentException if the node is not part of this topology
     */
    @NonNull
    String getRegion(@NonNull Node node);

    /**
     * Sets realistic latency and jitter based on geographic distribution. Applies different latency characteristics for
     * same-region, same-continent, and intercontinental connections based on the provided configuration.
     *
     * <p>If no {@link GeographicLatencyConfiguration} is set, the default
     * {@link GeographicLatencyConfiguration#DEFAULT} is used.
     *
     * @param configuration the geographic latency configuration to apply
     * @throws NullPointerException if {@code config} is {@code null}
     */
    void setGeographicLatencyConfiguration(@NonNull GeographicLatencyConfiguration configuration);

    /**
     * Configuration for realistic geographic latency simulation. Defines the distribution of node connections across
     * geographic boundaries and the latency characteristics for each type of connection.
     */
    @SuppressWarnings("unused")
    record GeographicLatencyConfiguration(
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
                throw new IllegalArgumentException("Same-region percentage must be more than 0.0");
            }
            if (sameContinentPercent.value < 0.0) {
                throw new IllegalArgumentException("Same-continent percentage must be more than 0.0");
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
}
