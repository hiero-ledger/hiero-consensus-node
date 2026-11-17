// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ArrivalTimeClamp Test")
class ArrivalTimeClampTest {

    private static final long T = 1_000_000_000L;
    private static final long MILLI = 1_000_000L;

    private final ArrivalTimeClamp clamp = new ArrivalTimeClamp(2);

    @Test
    @DisplayName("an increasing arrival time is left alone")
    void increasingTimesAreUnchanged() {
        assertThat(clamp.clamp(0, 1, MessageRoute.BROADCAST, T)).isEqualTo(T);
        assertThat(clamp.clamp(0, 1, MessageRoute.BROADCAST, T + 5 * MILLI)).isEqualTo(T + 5 * MILLI);
    }

    @Test
    @DisplayName("an arrival time before the previous one of the same kind is moved just after it")
    void earlierTimeIsClampedWithinAKind() {
        clamp.clamp(0, 1, MessageRoute.BROADCAST, T + 10 * MILLI);

        assertThat(clamp.clamp(0, 1, MessageRoute.BROADCAST, T)).isEqualTo(T + 10 * MILLI + 1);
        assertThat(clamp.clamp(0, 1, MessageRoute.BROADCAST, T)).isEqualTo(T + 10 * MILLI + 2);
    }

    @Test
    @DisplayName("each kind of route is clamped independently")
    void kindsDoNotConstrainEachOther() {
        clamp.clamp(0, 1, MessageRoute.SYNC, T + 100 * MILLI);

        assertThat(clamp.clamp(0, 1, MessageRoute.BROADCAST, T)).isEqualTo(T);
        assertThat(clamp.clamp(0, 1, MessageRoute.SYNC, T + MILLI)).isEqualTo(T + 100 * MILLI + 1);
    }

    @Test
    @DisplayName("each connection is clamped independently")
    void connectionsDoNotConstrainEachOther() {
        clamp.clamp(0, 1, MessageRoute.BROADCAST, T + 100 * MILLI);

        assertThat(clamp.clamp(1, 0, MessageRoute.BROADCAST, T)).isEqualTo(T);
    }
}
