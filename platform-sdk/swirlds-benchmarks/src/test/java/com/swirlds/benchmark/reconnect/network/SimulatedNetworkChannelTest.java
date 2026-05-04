// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SimulatedNetworkChannelTest {

    @Test
    void loopbackProfileIgnoresTimingAndBackpressure() {
        final NetworkSimulationConfig config =
                NetworkSimulationConfig.resolve(NetworkProfile.LOOPBACK, 500, 1_000, 131_072);

        assertEquals(NetworkProfile.LOOPBACK, config.profile());
        assertEquals(0, config.latencyNanos());
        assertEquals(Long.MAX_VALUE, config.bandwidthBytesPerSecond());
        assertEquals(Integer.MAX_VALUE, config.inflightBytesLimit());
    }

    @Test
    void realisticProfileConvertsUnits() {
        final NetworkSimulationConfig config =
                NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, 500, 1_000, 131_072);

        assertEquals(NetworkProfile.REALISTIC, config.profile());
        assertEquals(500_000, config.latencyNanos());
        assertEquals(125_000_000L, config.bandwidthBytesPerSecond());
        assertEquals(131_072, config.inflightBytesLimit());
    }

    @Test
    void realisticProfileRejectsInvalidValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, -1, 1_000, 131_072));
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, 500, 0, 131_072));
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, 500, 1_000, 0));
    }

    @Test
    void statsExposeCounterSnapshot() {
        final SimulatedNetworkStats stats = new SimulatedNetworkStats(10, 7, 4);

        assertEquals(10, stats.bytesWritten());
        assertEquals(7, stats.bytesRead());
        assertEquals(4, stats.maxInflightBytes());
        assertTrue(stats.toString().contains("bytesWritten=10"));
    }
}
