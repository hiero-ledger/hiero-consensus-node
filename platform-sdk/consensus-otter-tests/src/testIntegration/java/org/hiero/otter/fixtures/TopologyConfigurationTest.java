// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.assertj.core.data.Percentage;
import org.hiero.otter.fixtures.network.MeshTopologyConfiguration;
import org.hiero.otter.fixtures.network.utils.BandwidthLimit;
import org.hiero.otter.fixtures.network.utils.GeographicLatencyConfiguration;
import org.hiero.otter.fixtures.network.utils.LatencyRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test cases for topology configuration functionality.
 *
 * This test class verifies that users can configure network topologies
 * before adding any nodes to the network using the new configuration objects
 * and methods.
 */
class TopologyConfigurationTest {

    @Test
    @DisplayName("MeshTopologyConfiguration can be created with default values")
    void testMeshTopologyConfigurationDefault() {
        final MeshTopologyConfiguration config = MeshTopologyConfiguration.DEFAULT;

        assertNotNull(config);
        assertEquals(Duration.ofMillis(200), config.averageLatency());
        assertEquals(5.0, config.jitter().value);
        assertTrue(config.bandwidth().isUnlimited());
    }

    @Test
    @DisplayName("MeshTopologyConfiguration can be customized with withAverageLatency")
    void testMeshTopologyConfigurationWithLatency() {
        final Duration customLatency = Duration.ofMillis(500);
        final MeshTopologyConfiguration config =
                MeshTopologyConfiguration.DEFAULT.withAverageLatency(customLatency);

        assertEquals(customLatency, config.averageLatency());
        assertEquals(5.0, config.jitter().value);
    }

    @Test
    @DisplayName("MeshTopologyConfiguration can be customized with withJitter")
    void testMeshTopologyConfigurationWithJitter() {
        final Percentage customJitter = Percentage.withPercentage(10);
        final MeshTopologyConfiguration config =
                MeshTopologyConfiguration.DEFAULT.withJitter(customJitter);

        assertEquals(Duration.ofMillis(200), config.averageLatency());
        assertEquals(10.0, config.jitter().value);
    }

    @Test
    @DisplayName("MeshTopologyConfiguration can be customized with withBandwidth")
    void testMeshTopologyConfigurationWithBandwidth() {
        final BandwidthLimit customBandwidth = BandwidthLimit.ofMegabytesPerSecond(10);
        final MeshTopologyConfiguration config =
                MeshTopologyConfiguration.DEFAULT.withBandwidth(customBandwidth);

        assertEquals(Duration.ofMillis(200), config.averageLatency());
        assertEquals(customBandwidth, config.bandwidth());
    }

    @Test
    @DisplayName("MeshTopologyConfiguration supports builder pattern chaining")
    void testMeshTopologyConfigurationChaining() {
        final MeshTopologyConfiguration config = MeshTopologyConfiguration.DEFAULT
                .withAverageLatency(Duration.ofMillis(300))
                .withJitter(Percentage.withPercentage(7))
                .withBandwidth(BandwidthLimit.ofMegabytesPerSecond(5));

        assertEquals(Duration.ofMillis(300), config.averageLatency());
        assertEquals(7.0, config.jitter().value);
        assertEquals(5_120, config.bandwidth().toKilobytesPerSecond()); // 5 MB = 5120 KB
    }

    @Test
    @DisplayName("MeshTopologyConfiguration validates that latency is non-negative")
    void testMeshTopologyConfigurationNegativeLatency() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MeshTopologyConfiguration(
                        Duration.ofMillis(-100),
                        Percentage.withPercentage(5),
                        BandwidthLimit.UNLIMITED_BANDWIDTH));
    }

    @Test
    @DisplayName("GeographicLatencyConfiguration can be customized")
    void testGeographicLatencyConfigurationCustomization() {
        final LatencyRange customRange = LatencyRange.of(
                Duration.ofMillis(10),
                Duration.ofMillis(40),
                Percentage.withPercentage(8));

        final GeographicLatencyConfiguration config = GeographicLatencyConfiguration.DEFAULT
                .withSameRegionLatency(customRange);

        assertEquals(customRange, config.sameRegion());
    }

    @Test
    @DisplayName("MeshTopologyConfiguration rejects null parameters")
    void testMeshTopologyConfigurationNullChecking() {
        assertThrows(
                NullPointerException.class,
                () -> MeshTopologyConfiguration.DEFAULT.withAverageLatency(null));

        assertThrows(
                NullPointerException.class,
                () -> MeshTopologyConfiguration.DEFAULT.withJitter(null));

        assertThrows(
                NullPointerException.class,
                () -> MeshTopologyConfiguration.DEFAULT.withBandwidth(null));
    }
}
