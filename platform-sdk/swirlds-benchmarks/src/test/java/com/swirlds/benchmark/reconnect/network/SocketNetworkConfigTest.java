// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SocketNetworkConfigTest {

    @Test
    void loopbackProfileIgnoresShapingParameters() {
        final SocketNetworkConfig config = SocketNetworkConfig.resolve(NetworkProfile.LOOPBACK, 500, 1_000);

        assertEquals(NetworkProfile.LOOPBACK, config.profile());
        assertEquals(0, config.latencyNanos());
        assertEquals(Long.MAX_VALUE, config.bandwidthBytesPerSecond());
    }

    @Test
    void realisticProfileConvertsUnits() {
        final SocketNetworkConfig config = SocketNetworkConfig.resolve(NetworkProfile.REALISTIC, 500, 1_000);

        assertEquals(NetworkProfile.REALISTIC, config.profile());
        assertEquals(500_000, config.latencyNanos());
        assertEquals(125_000_000L, config.bandwidthBytesPerSecond());
    }

    @Test
    void realisticProfileRejectsInvalidValues() {
        assertThrows(
                IllegalArgumentException.class, () -> SocketNetworkConfig.resolve(NetworkProfile.REALISTIC, -1, 1_000));
        assertThrows(
                IllegalArgumentException.class, () -> SocketNetworkConfig.resolve(NetworkProfile.REALISTIC, 500, 0));
    }
}
