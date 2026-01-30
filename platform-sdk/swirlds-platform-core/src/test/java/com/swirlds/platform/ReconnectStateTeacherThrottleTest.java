// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.reconnect.ReconnectStateTeacherThrottle;
import java.time.Instant;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.reconnect.config.ReconnectConfig;
import org.hiero.consensus.reconnect.config.ReconnectConfig_;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Reconnect Throttle Tests")
class ReconnectStateTeacherThrottleTest {

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
        final ReconnectStateTeacherThrottle reconnectStateTeacherThrottle =
                new ReconnectStateTeacherThrottle(buildSettings("10m"), Time.getCurrent());

        assertTrue(reconnectStateTeacherThrottle.initiateReconnect(NodeId.of(0)), "reconnect should be allowed");
        assertFalse(reconnectStateTeacherThrottle.initiateReconnect(NodeId.of(1)), "reconnect should be blocked");
        reconnectStateTeacherThrottle.reconnectAttemptFinished();

        assertTrue(reconnectStateTeacherThrottle.initiateReconnect(NodeId.of(1)), "reconnect should be allowed");
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Simultaneous Reconnect Test")
    void repeatedReconnectTest() {
        final ReconnectStateTeacherThrottle reconnectStateTeacherThrottle =
                new ReconnectStateTeacherThrottle(buildSettings("1s"), Time.getCurrent());
        reconnectStateTeacherThrottle.setCurrentTime(() -> Instant.ofEpochMilli(0));

        assertTrue(reconnectStateTeacherThrottle.initiateReconnect(NodeId.of(0)), "reconnect should be allowed");
        reconnectStateTeacherThrottle.reconnectAttemptFinished();
        assertFalse(reconnectStateTeacherThrottle.initiateReconnect(NodeId.of(0)), "reconnect should be blocked");

        assertTrue(reconnectStateTeacherThrottle.initiateReconnect(NodeId.of(1)), "reconnect should be allowed");
        reconnectStateTeacherThrottle.reconnectAttemptFinished();
        assertFalse(reconnectStateTeacherThrottle.initiateReconnect(NodeId.of(1)), "reconnect should be blocked");

        reconnectStateTeacherThrottle.setCurrentTime(() -> Instant.ofEpochMilli(2000));

        assertTrue(reconnectStateTeacherThrottle.initiateReconnect(NodeId.of(0)), "reconnect should be allowed");
        reconnectStateTeacherThrottle.reconnectAttemptFinished();
        assertTrue(reconnectStateTeacherThrottle.initiateReconnect(NodeId.of(1)), "reconnect should be allowed");
        reconnectStateTeacherThrottle.reconnectAttemptFinished();
    }

    /**
     * As membership in the network changes, we should not keep reconnect records forever or else the records will grow
     * indefinitely.
     */
    @Test
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Many Node Test")
    void manyNodeTest() {

        final ReconnectStateTeacherThrottle reconnectStateTeacherThrottle =
                new ReconnectStateTeacherThrottle(buildSettings("1s"), Time.getCurrent());
        int time = 0;
        final int now = time;
        reconnectStateTeacherThrottle.setCurrentTime(() -> Instant.ofEpochMilli(now));

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 100; j++) {
                // Each request is for a unique node
                reconnectStateTeacherThrottle.initiateReconnect(NodeId.of((i + 1000) * (j + 1)));
                reconnectStateTeacherThrottle.reconnectAttemptFinished();

                assertTrue(
                        reconnectStateTeacherThrottle.getNumberOfRecentReconnects() <= 100,
                        "old requests should have been forgotten");
            }
            if (i + 1 < 3) {
                time += 2_000;
                final int later = time;
                reconnectStateTeacherThrottle.setCurrentTime(() -> Instant.ofEpochMilli(later));
            }
        }
    }
}
