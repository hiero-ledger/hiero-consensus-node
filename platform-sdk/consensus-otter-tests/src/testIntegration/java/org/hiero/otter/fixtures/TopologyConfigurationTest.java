// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.assertj.core.data.Percentage;
import org.hiero.otter.fixtures.network.BandwidthLimit;
import org.hiero.otter.fixtures.network.GeoMeshTopologyConfiguration;
import org.hiero.otter.fixtures.network.LatencyRange;
import org.hiero.otter.fixtures.network.MeshTopologyConfiguration;
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

        assertThat(config).as("DEFAULT configuration should not be null").isNotNull();
        assertThat(config.averageLatency())
                .as("Default latency should be 200ms")
                .isEqualTo(Duration.ofMillis(200));
        assertThat(config.jitter().value).as("Default jitter should be 5.0%").isEqualTo(5.0);
        assertThat(config.bandwidth().isUnlimited())
                .as("Default bandwidth should be unlimited")
                .isTrue();
    }

    @Test
    @DisplayName("MeshTopologyConfiguration can be customized with withAverageLatency")
    void testMeshTopologyConfigurationWithLatency() {
        final Duration customLatency = Duration.ofMillis(500);
        final MeshTopologyConfiguration config = MeshTopologyConfiguration.DEFAULT.withAverageLatency(customLatency);

        assertThat(config.averageLatency())
                .as("Latency should be updated to custom value")
                .isEqualTo(customLatency);
        assertThat(config.jitter().value).as("Jitter should remain unchanged").isEqualTo(5.0);
    }

    @Test
    @DisplayName("MeshTopologyConfiguration can be customized with withJitter")
    void testMeshTopologyConfigurationWithJitter() {
        final Percentage customJitter = Percentage.withPercentage(10);
        final MeshTopologyConfiguration config = MeshTopologyConfiguration.DEFAULT.withJitter(customJitter);

        assertThat(config.averageLatency())
                .as("Latency should remain unchanged")
                .isEqualTo(Duration.ofMillis(200));
        assertThat(config.jitter().value)
                .as("Jitter should be updated to custom value")
                .isEqualTo(10.0);
    }

    @Test
    @DisplayName("MeshTopologyConfiguration can be customized with withBandwidth")
    void testMeshTopologyConfigurationWithBandwidth() {
        final BandwidthLimit customBandwidth = BandwidthLimit.ofMegabytesPerSecond(10);
        final MeshTopologyConfiguration config = MeshTopologyConfiguration.DEFAULT.withBandwidth(customBandwidth);

        assertThat(config.averageLatency())
                .as("Latency should remain unchanged")
                .isEqualTo(Duration.ofMillis(200));
        assertThat(config.bandwidth())
                .as("Bandwidth should be updated to custom value")
                .isEqualTo(customBandwidth);
    }

    @Test
    @DisplayName("MeshTopologyConfiguration supports builder pattern chaining")
    void testMeshTopologyConfigurationChaining() {
        final MeshTopologyConfiguration config = MeshTopologyConfiguration.DEFAULT
                .withAverageLatency(Duration.ofMillis(300))
                .withJitter(Percentage.withPercentage(7))
                .withBandwidth(BandwidthLimit.ofMegabytesPerSecond(5));

        assertThat(config.averageLatency())
                .as("Chained latency should be 300ms")
                .isEqualTo(Duration.ofMillis(300));
        assertThat(config.jitter().value).as("Chained jitter should be 7.0%").isEqualTo(7.0);
        assertThat(config.bandwidth().toKilobytesPerSecond())
                .as("Chained bandwidth should be 5120 KB/s (5 MB/s)") // 5 MB = 5120 KB
                .isEqualTo(5_120);
    }

    @Test
    @DisplayName("MeshTopologyConfiguration validates that latency is non-negative")
    void testMeshTopologyConfigurationNegativeLatency() {
        assertThatThrownBy(() -> new MeshTopologyConfiguration(
                        Duration.ofMillis(-100), Percentage.withPercentage(5), BandwidthLimit.UNLIMITED_BANDWIDTH))
                .as("Should throw IllegalArgumentException for negative latency")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("GeoMeshTopologyConfiguration can be customized")
    void testGeoMeshTopologyConfigurationCustomization() {
        final LatencyRange customRange =
                LatencyRange.of(Duration.ofMillis(10), Duration.ofMillis(40), Percentage.withPercentage(8));

        final GeoMeshTopologyConfiguration config =
                GeoMeshTopologyConfiguration.DEFAULT.withSameRegionLatency(customRange);

        assertThat(config.sameRegion())
                .as("Same region latency should be updated to custom range")
                .isEqualTo(customRange);
    }

    @Test
    @DisplayName("MeshTopologyConfiguration rejects null parameters")
    void testMeshTopologyConfigurationNullChecking() {
        assertThatThrownBy(() -> MeshTopologyConfiguration.DEFAULT.withAverageLatency(null))
                .as("Should throw NullPointerException when latency is null")
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> MeshTopologyConfiguration.DEFAULT.withJitter(null))
                .as("Should throw NullPointerException when jitter is null")
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> MeshTopologyConfiguration.DEFAULT.withBandwidth(null))
                .as("Should throw NullPointerException when bandwidth is null")
                .isInstanceOf(NullPointerException.class);
    }
}
