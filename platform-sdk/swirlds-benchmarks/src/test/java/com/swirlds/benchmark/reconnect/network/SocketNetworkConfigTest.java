// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SocketNetworkConfigTest {

    @Test
    void loopbackProfilePreservesTargetButHasNoSchedulingComponents() {
        final SocketNetworkConfig config = SocketNetworkConfig.resolve(NetworkProfile.LOOPBACK, 500, 1_000);

        assertEquals(NetworkProfile.LOOPBACK, config.profile());
        assertEquals(500_000, config.configuredLatencyNanos());
        assertEquals(125_000_000L, config.configuredBandwidthBytesPerSecond());
        assertEquals(0, config.modeledLatencyNanos());
        assertEquals(Long.MAX_VALUE, config.modeledBandwidthBytesPerSecond());
        assertEquals(0, config.releaseQuantumNanos());
        assertEquals(0, config.maxObservedRangeBytes());
        assertFalse(config.visibilitySchedulingActive());
        assertFalse(config.latencyShapingActive());
        assertFalse(config.bandwidthShapingActive());
    }

    @Test
    void realisticProfileConvertsAndModelsConfiguredValues() {
        final SocketNetworkConfig config = SocketNetworkConfig.resolve(NetworkProfile.REALISTIC, 500, 1_000);

        assertEquals(NetworkProfile.REALISTIC, config.profile());
        assertEquals(500_000, config.configuredLatencyNanos());
        assertEquals(125_000_000L, config.configuredBandwidthBytesPerSecond());
        assertEquals(500_000, config.modeledLatencyNanos());
        assertEquals(125_000_000L, config.modeledBandwidthBytesPerSecond());
        assertEquals(50_000, config.releaseQuantumNanos());
        assertEquals(6_250, config.maxObservedRangeBytes());
        assertTrue(config.visibilitySchedulingActive());
        assertTrue(config.latencyShapingActive());
        assertTrue(config.bandwidthShapingActive());
    }

    @Test
    void calibratedProfileUsesTenPercentLatencyQuantumAndMatchingRange() {
        final SocketNetworkConfig config = SocketNetworkConfig.resolve(NetworkProfile.REALISTIC, 270, 200);

        assertEquals(270_000, config.configuredLatencyNanos());
        assertEquals(25_000_000, config.configuredBandwidthBytesPerSecond());
        assertEquals(27_000, config.releaseQuantumNanos());
        assertEquals(675, config.maxObservedRangeBytes());
    }

    @Test
    void instrumentedLoopbackUsesTargetDerivedGranularityWithoutShaping() {
        final SocketNetworkConfig realistic = SocketNetworkConfig.resolve(NetworkProfile.REALISTIC, 270, 200);
        final SocketNetworkConfig instrumented =
                SocketNetworkConfig.resolve(NetworkProfile.INSTRUMENTED_LOOPBACK, 270, 200);

        assertEquals(270_000, instrumented.configuredLatencyNanos());
        assertEquals(25_000_000, instrumented.configuredBandwidthBytesPerSecond());
        assertEquals(0, instrumented.modeledLatencyNanos());
        assertEquals(Long.MAX_VALUE, instrumented.modeledBandwidthBytesPerSecond());
        assertEquals(realistic.releaseQuantumNanos(), instrumented.releaseQuantumNanos());
        assertEquals(realistic.maxObservedRangeBytes(), instrumented.maxObservedRangeBytes());
        assertTrue(instrumented.visibilitySchedulingActive());
        assertFalse(instrumented.latencyShapingActive());
        assertFalse(instrumented.bandwidthShapingActive());
    }

    @Test
    void zeroLatencyUsesFiftyMicrosecondQuantum() {
        final SocketNetworkConfig config = SocketNetworkConfig.resolve(NetworkProfile.REALISTIC, 0, 200);

        assertEquals(50_000, config.releaseQuantumNanos());
        assertEquals(1_250, config.maxObservedRangeBytes());
        assertFalse(config.latencyShapingActive());
        assertTrue(config.bandwidthShapingActive());
    }

    @Test
    void observedRangeIsBoundedBetweenOneByteAndEightKibibytes() {
        final SocketNetworkConfig oneByte = SocketNetworkConfig.resolve(NetworkProfile.REALISTIC, 1, 1);
        final SocketNetworkConfig capped = SocketNetworkConfig.resolve(NetworkProfile.REALISTIC, 500, 2_000);

        assertEquals(1, oneByte.maxObservedRangeBytes());
        assertEquals(8 * 1_024, capped.maxObservedRangeBytes());
    }

    @Test
    void everyProfileRejectsInvalidTargetValues() {
        for (final NetworkProfile profile : NetworkProfile.values()) {
            assertThrows(IllegalArgumentException.class, () -> SocketNetworkConfig.resolve(profile, -1, 1_000));
            assertThrows(IllegalArgumentException.class, () -> SocketNetworkConfig.resolve(profile, 500, 0));
            assertThrows(IllegalArgumentException.class, () -> SocketNetworkConfig.resolve(profile, 500, -1));
        }
        assertThrows(NullPointerException.class, () -> SocketNetworkConfig.resolve(null, 500, 1_000));
    }

    @Test
    void unitConversionsRejectOverflow() {
        assertThrows(
                ArithmeticException.class,
                () -> SocketNetworkConfig.resolve(NetworkProfile.REALISTIC, Long.MAX_VALUE, 1));
        assertThrows(
                ArithmeticException.class,
                () -> SocketNetworkConfig.resolve(NetworkProfile.REALISTIC, 1, Long.MAX_VALUE));
    }
}
