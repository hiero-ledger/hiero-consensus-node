// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.network;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.internal.network.GeoMeshTopologyImpl.Location;
import org.hiero.otter.fixtures.network.utils.GeographicLatencyConfiguration;

/**
 * Utility class for distributing nodes geographically based on a target latency configuration.
 *
 * <p>The algorithm uses a brute-force approach to evaluate potential placements for a new node,
 * scoring each configuration based on how well it matches the desired distribution of nodes across regions and
 * continents. The placement that minimizes the difference from the target distribution is selected. The difference is
 * calculated using a simple squared error metric.
 *
 * <p>As we use a greedy approach, the algorithm may not always find the optimal solution. However,
 * it allows us to add nodes incrementally and should provide a reasonable approximation for typical use cases with a
 * moderate number of nodes.
 */
public class GeoDistributor {

    private static final List<String> CONTINENT_NAMES = List.of(
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
     * <p>The algorithm evaluates all placement options for the new node and scores each. To calculate the score of each
     * location, the node is temporarily added to the location, scored, and then removed. The best score out of all
     * available options is returned.
     *
     * @param configuration the target geographic latency configuration
     * @param nodes the current mapping of nodes to their geographic locations
     * @return the calculated location for the new node
     */
    public static Location calculateNextLocation(
            @NonNull final GeographicLatencyConfiguration configuration, @NonNull final Map<Node, Location> nodes) {
        requireNonNull(configuration);

        final List<Continent> continents = extractContinents(nodes);

        Location bestOption = null;
        double lowestError = Double.POSITIVE_INFINITY;

        // Option 1: Add the node to an existing region
        for (final Continent continent : continents) {
            for (final Region region : continent.regions) {
                region.nodeCount++;
                final double currentError = scoreConfiguration(configuration, continents);
                if (currentError < lowestError) {
                    lowestError = currentError;
                    bestOption = new Location(continent.name, region.name);
                }
                region.nodeCount--;
            }
        }

        // Option 2: Add the node to a new region in an existing continent
        for (final Continent continent : continents) {
            final int regionCount = continent.regions.size();
            final Region newRegion = new Region("Region-" + (regionCount + 1));
            continent.regions.add(newRegion);
            final double currentError = scoreConfiguration(configuration, continents);
            if (currentError < lowestError) {
                lowestError = currentError;
                bestOption = new Location(continent.name, newRegion.name);
            }
            continent.regions.remove(newRegion);
        }

        // Option 3: Add the node to a new continent and new region
        final int continentCount = continents.size();
        if (continentCount < CONTINENT_NAMES.size()) {
            final Region newRegion = new Region("Region-1");
            final Continent newContinent = new Continent(CONTINENT_NAMES.get(continentCount), newRegion);
            continents.add(newContinent);
            final double currentError = scoreConfiguration(configuration, continents);
            if (currentError < lowestError) {
                bestOption = new Location(newContinent.name, newRegion.name);
            }
        }

        return bestOption;
    }

    private static List<Continent> extractContinents(final Map<Node, Location> nodes) {
        final Map<String, Map<String, Long>> groups = nodes.values().stream()
                .collect(groupingBy(
                        Location::continent, TreeMap::new, groupingBy(Location::region, TreeMap::new, counting())));
        return groups.entrySet().stream()
                .map(entry -> {
                    final List<Region> regions = entry.getValue().entrySet().stream()
                            .map(regionEntry -> new Region(regionEntry.getKey(), regionEntry.getValue()))
                            .collect(Collectors.toCollection(ArrayList::new));
                    return new Continent(entry.getKey(), regions);
                })
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static double scoreConfiguration(
            @NonNull final GeographicLatencyConfiguration configuration, @NonNull final List<Continent> continents) {
        final long totalNodes = continents.stream()
                .flatMap(continent -> continent.regions.stream())
                .mapToLong(region -> region.nodeCount)
                .sum();
        if (totalNodes < 2) {
            return 0.0;
        }
        long sameRegionPairs = 0;
        long sameContinentPairs = 0;
        for (final Continent continent : continents) {
            final long localTotalNodes = continent.regions.stream()
                    .mapToLong(region -> region.nodeCount)
                    .sum();
            final long localTotalPairs = localTotalNodes * (localTotalNodes - 1) / 2;
            final long localSameRegionPairs = continent.regions.stream()
                    .mapToLong(region -> region.nodeCount * (region.nodeCount - 1) / 2)
                    .sum();
            final long localSameContinentPairs = localTotalPairs - localSameRegionPairs;
            sameRegionPairs += localSameRegionPairs;
            sameContinentPairs += localSameContinentPairs;
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

    /**
     * Represents a geographic region with a name and the number of nodes assigned to it.
     */
    private static class Region {
        private final String name;
        private long nodeCount;

        private Region(@NonNull final String name) {
            this(name, 1);
        }

        private Region(@NonNull final String name, final long nodeCount) {
            this.name = name;
            this.nodeCount = nodeCount;
        }
    }

    /**
     * Represents a continent with a name and a list of regions within it.
     */
    private record Continent(@NonNull String name, @NonNull List<Region> regions) {
        private Continent(@NonNull final String name, @NonNull final Region region) {
            this(name, new ArrayList<>());
            regions.add(region);
        }
    }
}
