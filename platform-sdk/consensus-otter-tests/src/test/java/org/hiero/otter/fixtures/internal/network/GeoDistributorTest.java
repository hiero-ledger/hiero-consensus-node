// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Percentage.withPercentage;
import static org.hiero.otter.fixtures.internal.network.GeoDistributor.calculateNextLocation;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.internal.network.GeoMeshTopologyImpl.Location;
import org.hiero.otter.fixtures.network.utils.GeographicLatencyConfiguration;
import org.hiero.otter.fixtures.network.utils.LatencyRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GeoDistributor Test")
class GeoDistributorTest {

    private GeographicLatencyConfiguration defaultConfig;

    @BeforeEach
    void setUp() {
        defaultConfig = new GeographicLatencyConfiguration(
                withPercentage(20.0),
                withPercentage(40.0),
                LatencyRange.of(Duration.ofMillis(10)),
                LatencyRange.of(Duration.ofMillis(50)),
                LatencyRange.of(Duration.ofMillis(150)));
    }

    @Test
    @DisplayName("Calculate next location for empty network")
    void calculateNextLocationEmptyNetwork() {
        final Map<Node, Location> nodes = new HashMap<>();

        final Location result = calculateNextLocation(defaultConfig, nodes);

        assertThat(result).isNotNull();
        assertThat(result.continent()).isEqualTo("AETHERMOOR");
        assertThat(result.region()).isEqualTo("Region-1");
    }

    @Test
    @DisplayName("Calculate next location with single existing node")
    void calculateNextLocationSingleNode() {
        final Map<Node, Location> nodes = new HashMap<>();
        final Node node1 = mock(Node.class);
        nodes.put(node1, new Location("AETHERMOOR", "Region-1"));

        final Location result = calculateNextLocation(defaultConfig, nodes);

        assertThat(result).isNotNull();
        // With 20% same-region, 40% same-continent target, and only 1 node,
        // algorithm chooses to add to a new region in same continent
        assertThat(result.continent()).isEqualTo("AETHERMOOR");
        assertThat(result.region()).isEqualTo("Region-2");
    }

    @Test
    @DisplayName("Calculate next location with nodes in multiple regions of same continent")
    void calculateNextLocationMultipleRegionsSameContinent() {
        final Map<Node, Location> nodes = new HashMap<>();
        final Node node1 = mock(Node.class);
        final Node node2 = mock(Node.class);
        nodes.put(node1, new Location("AETHERMOOR", "Region-1"));
        nodes.put(node2, new Location("AETHERMOOR", "Region-2"));

        final Location result = calculateNextLocation(defaultConfig, nodes);

        assertThat(result).isNotNull();
        // With 2 nodes in same continent but different regions, adding to new continent
        // helps balance toward the 40% intercontinental target
        assertThat(result.continent()).isEqualTo("BRIMHAVEN");
        assertThat(result.region()).isEqualTo("Region-1");
    }

    @Test
    @DisplayName("Calculate next location with nodes across multiple continents")
    void calculateNextLocationMultipleContinents() {
        final Map<Node, Location> nodes = new HashMap<>();
        final Node node1 = mock(Node.class);
        final Node node2 = mock(Node.class);
        final Node node3 = mock(Node.class);
        nodes.put(node1, new Location("AETHERMOOR", "Region-1"));
        nodes.put(node2, new Location("BRIMHAVEN", "Region-1"));
        nodes.put(node3, new Location("CRYSTALTHORNE", "Region-1"));

        final Location result = calculateNextLocation(defaultConfig, nodes);

        assertThat(result).isNotNull();
        // With 3 nodes all in different continents, current is 0% same-region, 0% same-continent
        // With deterministic ordering, algorithm chooses first continent alphabetically for new region
        assertThat(result.continent()).isEqualTo("AETHERMOOR");
        assertThat(result.region()).isEqualTo("Region-2");
    }

    @Test
    @DisplayName("Prefer adding to existing region when it optimizes score")
    void preferExistingRegion() {
        final Map<Node, Location> nodes = new HashMap<>();
        final Node node1 = mock(Node.class);
        nodes.put(node1, new Location("AETHERMOOR", "Region-1"));

        final GeographicLatencyConfiguration highSameRegionConfig = new GeographicLatencyConfiguration(
                withPercentage(80.0),
                withPercentage(15.0),
                LatencyRange.of(Duration.ofMillis(10)),
                LatencyRange.of(Duration.ofMillis(50)),
                LatencyRange.of(Duration.ofMillis(150)));

        final Location result = calculateNextLocation(highSameRegionConfig, nodes);

        assertThat(result).isNotNull();
        assertThat(result.continent()).isEqualTo("AETHERMOOR");
        assertThat(result.region()).isEqualTo("Region-1");
    }

    @Test
    @DisplayName("Calculate next location with complex distribution")
    void calculateNextLocationComplexDistribution() {
        final Map<Node, Location> nodes = new HashMap<>();
        // 5 nodes in AETHERMOOR Region-1
        for (int i = 0; i < 5; i++) {
            final Node node = mock(Node.class);
            nodes.put(node, new Location("AETHERMOOR", "Region-1"));
        }
        // 3 nodes in AETHERMOOR Region-2
        for (int i = 0; i < 3; i++) {
            final Node node = mock(Node.class);
            nodes.put(node, new Location("AETHERMOOR", "Region-2"));
        }
        // 4 nodes in BRIMHAVEN Region-1
        for (int i = 0; i < 4; i++) {
            final Node node = mock(Node.class);
            nodes.put(node, new Location("BRIMHAVEN", "Region-1"));
        }

        final Location result = calculateNextLocation(defaultConfig, nodes);

        assertThat(result).isNotNull();
        // With 12 nodes: 8 in AETHERMOOR (5 in R1, 3 in R2), 4 in BRIMHAVEN R1
        // Algorithm chooses best scoring option - new region in AETHERMOOR
        assertThat(result.continent()).isEqualTo("AETHERMOOR");
        assertThat(result.region()).isEqualTo("Region-3");
    }

    @Test
    @DisplayName("Handle large number of existing nodes")
    void handleLargeNumberOfNodes() {
        final Map<Node, Location> nodes = new HashMap<>();
        // 100 nodes all in AETHERMOOR, distributed across 5 regions (20 each)
        for (int i = 0; i < 100; i++) {
            final Node node = mock(Node.class);
            final String continent = "AETHERMOOR";
            final String region = "Region-" + (i % 5 + 1);
            nodes.put(node, new Location(continent, region));
        }

        final Location result = calculateNextLocation(defaultConfig, nodes);

        assertThat(result).isNotNull();
        // With all 100 nodes in one continent, desperately needs intercontinental distribution
        assertThat(result.continent()).isEqualTo("BRIMHAVEN");
        assertThat(result.region()).isEqualTo("Region-1");
    }

    @Test
    @DisplayName("Throw NullPointerException for null configuration")
    void throwsNullPointerForNullConfiguration() {
        final Map<Node, Location> nodes = new HashMap<>();

        assertThatThrownBy(() -> calculateNextLocation(null, nodes)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Throw NullPointerException for null nodes map")
    void throwsNullPointerForNullNodes() {
        assertThatThrownBy(() -> calculateNextLocation(defaultConfig, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("New region naming follows sequential pattern")
    void newRegionNamingPattern() {
        final Map<Node, Location> nodes = new HashMap<>();
        nodes.put(mock(Node.class), new Location("AETHERMOOR", "Region-1"));
        nodes.put(mock(Node.class), new Location("AETHERMOOR", "Region-2"));

        final GeographicLatencyConfiguration sameContinentPreferredConfig = new GeographicLatencyConfiguration(
                withPercentage(10.0),
                withPercentage(80.0),
                LatencyRange.of(Duration.ofMillis(10)),
                LatencyRange.of(Duration.ofMillis(50)),
                LatencyRange.of(Duration.ofMillis(150)));

        final Location result = calculateNextLocation(sameContinentPreferredConfig, nodes);

        assertThat(result).isNotNull();
        // With 80% same-continent target and 2 nodes already in AETHERMOOR,
        // algorithm should add new region following sequential naming pattern
        assertThat(result.continent()).isEqualTo("AETHERMOOR");
        assertThat(result.region()).isEqualTo("Region-3");
    }

    @Test
    @DisplayName("Score calculation with 100% same-region target")
    void scoreCalculationAllSameRegion() {
        final Map<Node, Location> nodes = new HashMap<>();
        nodes.put(mock(Node.class), new Location("AETHERMOOR", "Region-1"));

        final GeographicLatencyConfiguration allSameRegionConfig = new GeographicLatencyConfiguration(
                withPercentage(100.0),
                withPercentage(0.0),
                LatencyRange.of(Duration.ofMillis(10)),
                LatencyRange.of(Duration.ofMillis(50)),
                LatencyRange.of(Duration.ofMillis(150)));

        final Location result = calculateNextLocation(allSameRegionConfig, nodes);

        assertThat(result).isNotNull();
        // With 100% same-region target, should add to existing region
        assertThat(result.continent()).isEqualTo("AETHERMOOR");
        assertThat(result.region()).isEqualTo("Region-1");
    }

    @Test
    @DisplayName("Score calculation with 100% same-continent target")
    void scoreCalculationAllSameContinent() {
        final Map<Node, Location> nodes = new HashMap<>();
        nodes.put(mock(Node.class), new Location("AETHERMOOR", "Region-1"));

        final GeographicLatencyConfiguration allSameContinentConfig = new GeographicLatencyConfiguration(
                withPercentage(0.0),
                withPercentage(100.0),
                LatencyRange.of(Duration.ofMillis(10)),
                LatencyRange.of(Duration.ofMillis(50)),
                LatencyRange.of(Duration.ofMillis(150)));

        final Location result = calculateNextLocation(allSameContinentConfig, nodes);

        assertThat(result).isNotNull();
        // With 100% same-continent target, should add new region in same continent
        assertThat(result.continent()).isEqualTo("AETHERMOOR");
        assertThat(result.region()).isEqualTo("Region-2");
    }

    @Test
    @DisplayName("Score calculation with 100% intercontinental target")
    void scoreCalculationAllIntercontinental() {
        final Map<Node, Location> nodes = new HashMap<>();
        nodes.put(mock(Node.class), new Location("AETHERMOOR", "Region-1"));

        final GeographicLatencyConfiguration allIntercontinentalConfig = new GeographicLatencyConfiguration(
                withPercentage(0.0),
                withPercentage(0.0),
                LatencyRange.of(Duration.ofMillis(10)),
                LatencyRange.of(Duration.ofMillis(50)),
                LatencyRange.of(Duration.ofMillis(150)));

        final Location result = calculateNextLocation(allIntercontinentalConfig, nodes);

        assertThat(result).isNotNull();
        // With 0% same-region, 0% same-continent (100% intercontinental target)
        assertThat(result.continent()).isEqualTo("BRIMHAVEN");
        assertThat(result.region()).isEqualTo("Region-1");
    }

    @Test
    @DisplayName("When at continent limit (8), falls back to existing regions or new regions in existing continents")
    void maxContinentsFallbackBehavior() {
        final Map<Node, Location> nodes = new HashMap<>();

        // Setup: 7 continents in use
        nodes.put(mock(Node.class), new Location("AETHERMOOR", "Region-1"));
        nodes.put(mock(Node.class), new Location("BRIMHAVEN", "Region-1"));
        nodes.put(mock(Node.class), new Location("CRYSTALTHORNE", "Region-1"));
        nodes.put(mock(Node.class), new Location("DRAKENVOLD", "Region-1"));
        nodes.put(mock(Node.class), new Location("ELDERMYST", "Region-1"));
        nodes.put(mock(Node.class), new Location("FROSTSPIRE", "Region-1"));
        nodes.put(mock(Node.class), new Location("GOLDENREACH", "Region-1"));

        // Use configuration that strongly favors intercontinental distribution
        final GeographicLatencyConfiguration intercontinentalConfig = new GeographicLatencyConfiguration(
                withPercentage(0.0),
                withPercentage(0.0),
                LatencyRange.of(Duration.ofMillis(10)),
                LatencyRange.of(Duration.ofMillis(50)),
                LatencyRange.of(Duration.ofMillis(150)));

        // With the condition continentCount < CONTINENTS.size(),
        // with 7 continents (count=7), 7 < 8 is true, so 8th continent can be added
        final Location eighthNode = calculateNextLocation(intercontinentalConfig, nodes);
        assertThat(eighthNode.continent()).isEqualTo("HALLOWMERE");
        nodes.put(mock(Node.class), eighthNode);

        // Now at 8 continents (max), next addition should not create a 9th continent
        final Location afterEight = calculateNextLocation(intercontinentalConfig, nodes);
        assertThat(afterEight).isNotNull();
        assertThat(afterEight.continent())
                .isIn(
                        "AETHERMOOR",
                        "BRIMHAVEN",
                        "CRYSTALTHORNE",
                        "DRAKENVOLD",
                        "ELDERMYST",
                        "FROSTSPIRE",
                        "GOLDENREACH",
                        "HALLOWMERE");
    }

    @Test
    void test() {
        final Map<Node, Location> nodes = new HashMap<>();
        final Location loc1 = calculateNextLocation(defaultConfig, nodes);
        nodes.put(mock(Node.class), loc1);
        nodes.put(mock(Node.class), calculateNextLocation(defaultConfig, nodes));
        nodes.put(mock(Node.class), calculateNextLocation(defaultConfig, nodes));
        nodes.put(mock(Node.class), calculateNextLocation(defaultConfig, nodes));
        nodes.put(mock(Node.class), calculateNextLocation(defaultConfig, nodes));
    }
}
