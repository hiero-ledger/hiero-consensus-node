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
        assertThat(clamp(0, 1, MessageRoute.BROADCAST, T)).isEqualTo(T);
        assertThat(clamp(0, 1, MessageRoute.BROADCAST, T + 5 * MILLI)).isEqualTo(T + 5 * MILLI);
    }

    @Test
    @DisplayName("an arrival time before the previous one of the same kind is moved just after it")
    void earlierTimeIsClampedWithinAKind() {
        clamp(0, 1, MessageRoute.BROADCAST, T + 10 * MILLI);

        assertThat(clamp(0, 1, MessageRoute.BROADCAST, T)).isEqualTo(T + 10 * MILLI + 1);
        assertThat(clamp(0, 1, MessageRoute.BROADCAST, T)).isEqualTo(T + 10 * MILLI + 2);
    }

    @Test
    @DisplayName("each kind of route is clamped independently")
    void kindsDoNotConstrainEachOther() {
        clamp(0, 1, MessageRoute.SYNC, T + 100 * MILLI);

        assertThat(clamp(0, 1, MessageRoute.BROADCAST, T)).isEqualTo(T);
        assertThat(clamp(0, 1, MessageRoute.SYNC, T + MILLI)).isEqualTo(T + 100 * MILLI + 1);
    }

    @Test
    @DisplayName("an arrival time that is only peeked at does not hold up the next one")
    void peekDoesNotRecord() {
        clamp.peek(0, 1, MessageRoute.BROADCAST, T + 10 * MILLI);

        assertThat(clamp(0, 1, MessageRoute.BROADCAST, T)).isEqualTo(T);
    }

    @Test
    @DisplayName("each connection is clamped independently")
    void connectionsDoNotConstrainEachOther() {
        clamp(0, 1, MessageRoute.BROADCAST, T + 100 * MILLI);

        assertThat(clamp(1, 0, MessageRoute.BROADCAST, T)).isEqualTo(T);
    }

    /**
     * Clamps an arrival time and records it, as is done for a copy that is sent.
     */
    private long clamp(final int sender, final int receiver, final MessageRoute kind, final long proposed) {
        final long arrival = clamp.peek(sender, receiver, kind, proposed);
        clamp.commit(sender, receiver, kind, arrival);
        return arrival;
    }
}
