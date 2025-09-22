// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.network;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.internal.network.GeoMeshTopologyImpl.Location;
import org.hiero.otter.fixtures.network.GeoMeshTopology.GeographicLatencyConfiguration;

/**
 * Utility class for distributing nodes geographically based on a target latency configuration.
 *
 * <p>The algorithm uses a brute-force approach to evaluate potential placements for a new node,
 * scoring each configuration based on how well it matches the desired distribution of nodes across
 * regions and continents. The placement that minimizes the difference from the target distribution is
 * selected. The difference is calculated using a simple squared error metric.
 *
 * <p>As we use a greedy approach, the algorithm may not always find the optimal solution. However,
 * it allows us to add nodes incrementally and should provide a reasonable approximation for typical
 * use cases with a moderate number of nodes.
 */
public class GeoDistributor {

    private static final List<String> CONTINENTS = List.of(
            "AETHERMOOR",
            "BRIMHAVEN",
            "CRYSTALTHORNE",
            "DRAKENVOLD",
            "ELDERMYST",
            "FROSTSPIRE",
            "GOLDENREACH",
            "HALLOWMERE");

    private GeoDistributor() {}

    /**
     * Calculates the optimal geographic location for adding a new node to the network.
     *
     * @param configuration the target geographic latency configuration
     * @param nodes the current mapping of nodes to their geographic locations
     * @return the calculated location for the new node
     */
    public static Location calculateNextLocation(
            @NonNull final GeographicLatencyConfiguration configuration, @NonNull final Map<Node, Location> nodes) {
        requireNonNull(configuration);

        // Extract a structured map of continents to regions with their node counts
        final Map<String, Map<String, Long>> continents = nodes.values().stream()
                .collect(groupingBy(
                        Location::continent, LinkedHashMap::new, groupingBy(Location::region, LinkedHashMap::new, counting())));

        Location bestOption = null;
        double bestScore = Double.POSITIVE_INFINITY;

        // Option 1: Add the node to an existing region
        for (final Map.Entry<String, Map<String, Long>> entry : continents.entrySet()) {
            final String continent = entry.getKey();
            final Map<String, Long> regions = entry.getValue();
            for (final String region : regions.keySet()) {
                regions.put(region, regions.get(region) + 1);
                final double currentScore = scoreConfiguration(configuration, continents);
                if (currentScore < bestScore) {
                    bestScore = currentScore;
                    bestOption = new Location(continent, region);
                }
                regions.put(region, regions.get(region) - 1);
            }
        }

        // Option 2: Add the node to a new region in an existing continent
        for (final String continent : continents.keySet()) {
            final int regionCount = continents.get(continent).size();
            final String newRegion = "Region-" + (regionCount + 1);
            continents.get(continent).put(newRegion, 1L);
            final double currentScore = scoreConfiguration(configuration, continents);
            if (currentScore < bestScore) {
                bestScore = currentScore;
                bestOption = new Location(continent, newRegion);
            }
            continents.get(continent).remove(newRegion);
        }

        // Option 3: Add the node to a new continent and new region
        final int continentCount = continents.size();
        if (continentCount < CONTINENTS.size()) {
            final String newContinent = CONTINENTS.get(continentCount);
            final String newRegion = "Region-1";
            continents.put(newContinent, new HashMap<>(Map.of(newRegion, 1L)));
            final double currentScore = scoreConfiguration(configuration, continents);
            if (currentScore < bestScore) {
                bestOption = new Location(newContinent, newRegion);
            }
            continents.remove(newContinent);
        }

        return bestOption;
    }

    private static double scoreConfiguration(
            @NonNull final GeographicLatencyConfiguration configuration,
            @NonNull final Map<String, Map<String, Long>> continents) {
        final long totalNodes = continents.values().stream()
                .flatMap(regions -> regions.values().stream())
                .mapToLong(Long::longValue)
                .sum();
        if (totalNodes < 2) {
            return 0.0;
        }
        long sameRegionPairs = 0;
        long sameContinentPairs = 0;
        for (final Map<String, Long> regions : continents.values()) {
            for (final long count : regions.values()) {
                sameRegionPairs += count * (count - 1) / 2;
            }
            final long continentCount =
                    regions.values().stream().mapToLong(Long::longValue).sum();
            sameContinentPairs += continentCount * (continentCount - 1) / 2 - sameRegionPairs;
        }
        final long totalPairs = totalNodes * (totalNodes - 1) / 2;
        final double sameRegionPercent = (double) sameRegionPairs / totalPairs;
        final double sameContinentPercent = (double) (sameContinentPairs) / totalPairs;
        final double targetSameRegionPercent = configuration.sameRegionPercent().value / 100.0;
        final double targetSameContinentPercent = configuration.sameContinentPercent().value / 100.0;
        final double targetDifferentContinentPercent = 1.0 - targetSameRegionPercent - targetSameContinentPercent;
        return Math.pow(sameRegionPercent - targetSameRegionPercent, 2)
                + Math.pow(sameContinentPercent - targetSameContinentPercent, 2)
                + Math.pow((1.0 - sameRegionPercent - sameContinentPercent) - targetDifferentContinentPercent, 2);
    }
}
