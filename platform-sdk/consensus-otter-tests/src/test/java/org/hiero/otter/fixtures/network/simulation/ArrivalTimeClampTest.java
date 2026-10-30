// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ArrivalTimeClamp Test")
class ArrivalTimeClampTest {

    private static final ConnectionKey CONNECTION = new ConnectionKey(NodeId.of(0), NodeId.of(1));
    private static final ConnectionKey OTHER_CONNECTION = new ConnectionKey(NodeId.of(1), NodeId.of(0));
    private static final Instant T = Instant.parse("2024-01-01T00:00:00Z");

    private final ArrivalTimeClamp clamp = new ArrivalTimeClamp();

    @Test
    @DisplayName("an increasing arrival time is left alone")
    void increasingTimesAreUnchanged() {
        assertThat(clamp.clamp(CONNECTION, MessageRoute.BROADCAST, T)).isEqualTo(T);
        assertThat(clamp.clamp(CONNECTION, MessageRoute.BROADCAST, T.plusMillis(5)))
                .isEqualTo(T.plusMillis(5));
    }

    @Test
    @DisplayName("an arrival time before the previous one of the same kind is moved just after it")
    void earlierTimeIsClampedWithinAKind() {
        clamp.clamp(CONNECTION, MessageRoute.BROADCAST, T.plusMillis(10));

        assertThat(clamp.clamp(CONNECTION, MessageRoute.BROADCAST, T))
                .isEqualTo(T.plusMillis(10).plusNanos(1));
        assertThat(clamp.clamp(CONNECTION, MessageRoute.BROADCAST, T))
                .isEqualTo(T.plusMillis(10).plusNanos(2));
    }

    @Test
    @DisplayName("each kind of route is clamped independently")
    void kindsDoNotConstrainEachOther() {
        clamp.clamp(CONNECTION, MessageRoute.SYNC, T.plusMillis(100));

        assertThat(clamp.clamp(CONNECTION, MessageRoute.BROADCAST, T)).isEqualTo(T);
        assertThat(clamp.clamp(CONNECTION, MessageRoute.SYNC, T.plusMillis(1)))
                .isEqualTo(T.plusMillis(100).plusNanos(1));
    }

    @Test
    @DisplayName("each connection is clamped independently")
    void connectionsDoNotConstrainEachOther() {
        clamp.clamp(CONNECTION, MessageRoute.BROADCAST, T.plusMillis(100));

        assertThat(clamp.clamp(OTHER_CONNECTION, MessageRoute.BROADCAST, T)).isEqualTo(T);
    }
}
