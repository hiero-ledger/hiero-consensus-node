// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertNotNull(config, "DEFAULT configuration should not be null");
        assertEquals(Duration.ofMillis(200), config.averageLatency(), "Default latency should be 200ms");
        assertEquals(5.0, config.jitter().value, "Default jitter should be 5.0%");
        assertTrue(config.bandwidth().isUnlimited(), "Default bandwidth should be unlimited");
    }

    @Test
    @DisplayName("MeshTopologyConfiguration can be customized with withAverageLatency")
    void testMeshTopologyConfigurationWithLatency() {
        final Duration customLatency = Duration.ofMillis(500);
        final MeshTopologyConfiguration config =
                MeshTopologyConfiguration.DEFAULT.withAverageLatency(customLatency);

        assertEquals(customLatency, config.averageLatency(), "Latency should be updated to custom value");
        assertEquals(5.0, config.jitter().value, "Jitter should remain unchanged");
    }

    @Test
    @DisplayName("MeshTopologyConfiguration can be customized with withJitter")
    void testMeshTopologyConfigurationWithJitter() {
        final Percentage customJitter = Percentage.withPercentage(10);
        final MeshTopologyConfiguration config =
                MeshTopologyConfiguration.DEFAULT.withJitter(customJitter);

        assertEquals(Duration.ofMillis(200), config.averageLatency(), "Latency should remain unchanged");
        assertEquals(10.0, config.jitter().value, "Jitter should be updated to custom value");
    }

    @Test
    @DisplayName("MeshTopologyConfiguration can be customized with withBandwidth")
    void testMeshTopologyConfigurationWithBandwidth() {
        final BandwidthLimit customBandwidth = BandwidthLimit.ofMegabytesPerSecond(10);
        final MeshTopologyConfiguration config =
                MeshTopologyConfiguration.DEFAULT.withBandwidth(customBandwidth);

        assertEquals(Duration.ofMillis(200), config.averageLatency(), "Latency should remain unchanged");
        assertEquals(customBandwidth, config.bandwidth(), "Bandwidth should be updated to custom value");
    }

    @Test
    @DisplayName("MeshTopologyConfiguration supports builder pattern chaining")
    void testMeshTopologyConfigurationChaining() {
        final MeshTopologyConfiguration config = MeshTopologyConfiguration.DEFAULT
                .withAverageLatency(Duration.ofMillis(300))
                .withJitter(Percentage.withPercentage(7))
                .withBandwidth(BandwidthLimit.ofMegabytesPerSecond(5));

        assertEquals(Duration.ofMillis(300), config.averageLatency(), "Chained latency should be 300ms");
        assertEquals(7.0, config.jitter().value, "Chained jitter should be 7.0%");
        assertEquals(5_120, config.bandwidth().toKilobytesPerSecond(), "Chained bandwidth should be 5120 KB/s (5 MB/s)"); // 5 MB = 5120 KB
    }

    @Test
    @DisplayName("MeshTopologyConfiguration validates that latency is non-negative")
    void testMeshTopologyConfigurationNegativeLatency() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MeshTopologyConfiguration(
                        Duration.ofMillis(-100),
                        Percentage.withPercentage(5),
                        BandwidthLimit.UNLIMITED_BANDWIDTH),
                "Should throw IllegalArgumentException for negative latency");
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

        assertEquals(customRange, config.sameRegion(), "Same region latency should be updated to custom range");
    }

    @Test
    @DisplayName("MeshTopologyConfiguration rejects null parameters")
    void testMeshTopologyConfigurationNullChecking() {
        assertThrows(
                NullPointerException.class,
                () -> MeshTopologyConfiguration.DEFAULT.withAverageLatency(null),
                "Should throw NullPointerException when latency is null");

        assertThrows(
                NullPointerException.class,
                () -> MeshTopologyConfiguration.DEFAULT.withJitter(null),
                "Should throw NullPointerException when jitter is null");

        assertThrows(
                NullPointerException.class,
                () -> MeshTopologyConfiguration.DEFAULT.withBandwidth(null),
                "Should throw NullPointerException when bandwidth is null");
    }
}
