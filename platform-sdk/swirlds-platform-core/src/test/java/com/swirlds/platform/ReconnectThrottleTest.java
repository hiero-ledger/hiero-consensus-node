// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig_;
import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.reconnect.ReconnectThrottle;
import java.time.Instant;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Reconnect Throttle Tests")
class ReconnectThrottleTest {

    private ReconnectConfig buildSettings(final String minimumTimeBetweenReconnects) {
        final Configuration config = new TestConfigBuilder()
                .withValue(ReconnectConfig_.ACTIVE, "true")
                .withValue(ReconnectConfig_.ASYNC_STREAM_TIMEOUT, "0ms") // Not needed in Test
                .withValue(ReconnectConfig_.ASYNC_OUTPUT_STREAM_FLUSH, "0ms") // Not needed in Test
                .withValue(ReconnectConfig_.MINIMUM_TIME_BETWEEN_RECONNECTS, minimumTimeBetweenReconnects)
                .getOrCreateConfig();

        return config.getConfigData(ReconnectConfig.class);
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Simultaneous Reconnect Test")
    void simultaneousReconnectTest() {
        final ReconnectThrottle reconnectThrottle = new ReconnectThrottle(buildSettings("10m"), Time.getCurrent());

        assertTrue(reconnectThrottle.initiateReconnect(NodeId.of(0)), "reconnect should be allowed");
        assertFalse(reconnectThrottle.initiateReconnect(NodeId.of(1)), "reconnect should be blocked");
        reconnectThrottle.reconnectAttemptFinished();

        assertTrue(reconnectThrottle.initiateReconnect(NodeId.of(1)), "reconnect should be allowed");
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Simultaneous Reconnect Test")
    void repeatedReconnectTest() {
        final ReconnectThrottle reconnectThrottle = new ReconnectThrottle(buildSettings("1s"), Time.getCurrent());
        reconnectThrottle.setCurrentTime(() -> Instant.ofEpochMilli(0));

        assertTrue(reconnectThrottle.initiateReconnect(NodeId.of(0)), "reconnect should be allowed");
        reconnectThrottle.reconnectAttemptFinished();
        assertFalse(reconnectThrottle.initiateReconnect(NodeId.of(0)), "reconnect should be blocked");

        assertTrue(reconnectThrottle.initiateReconnect(NodeId.of(1)), "reconnect should be allowed");
        reconnectThrottle.reconnectAttemptFinished();
        assertFalse(reconnectThrottle.initiateReconnect(NodeId.of(1)), "reconnect should be blocked");

        reconnectThrottle.setCurrentTime(() -> Instant.ofEpochMilli(2000));

        assertTrue(reconnectThrottle.initiateReconnect(NodeId.of(0)), "reconnect should be allowed");
        reconnectThrottle.reconnectAttemptFinished();
        assertTrue(reconnectThrottle.initiateReconnect(NodeId.of(1)), "reconnect should be allowed");
        reconnectThrottle.reconnectAttemptFinished();
    }

    /**
     * As membership in the network changes, we should not keep reconnect records forever or else the records will grow
     * indefinitely.
     */
    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Many Node Test")
    void manyNodeTest() {

        final ReconnectThrottle reconnectThrottle = new ReconnectThrottle(buildSettings("1s"), Time.getCurrent());
        int time = 0;
        final int now = time;
        reconnectThrottle.setCurrentTime(() -> Instant.ofEpochMilli(now));

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 100; j++) {
                // Each request is for a unique node
                reconnectThrottle.initiateReconnect(NodeId.of((i + 1000) * (j + 1)));
                reconnectThrottle.reconnectAttemptFinished();

                assertTrue(
                        reconnectThrottle.getNumberOfRecentReconnects() <= 100,
                        "old requests should have been forgotten");
            }
            if (i + 1 < 3) {
                time += 2_000;
                final int later = time;
                reconnectThrottle.setCurrentTime(() -> Instant.ofEpochMilli(later));
            }
        }
    }
}
