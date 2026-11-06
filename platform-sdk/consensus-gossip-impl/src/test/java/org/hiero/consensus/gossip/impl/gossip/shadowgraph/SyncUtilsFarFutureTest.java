// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.shadowgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link SyncUtils#isFarFutureEvent}: an event is a far future event if its birth round is greater than the
 * pending round plus {@code gossip.farFutureEventOffset}, and no event is a far future event if the offset is negative.
 */
class SyncUtilsFarFutureTest {

    private static final long PENDING_ROUND = 11;

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 5, 10})
    void boundaryIsPendingRoundPlusOffset(final long offset) {
        final long maxAcceptable = PENDING_ROUND + offset;

        assertThat(SyncUtils.isFarFutureEvent(offset, 1, PENDING_ROUND)).isFalse();
        assertThat(SyncUtils.isFarFutureEvent(offset, PENDING_ROUND, PENDING_ROUND))
                .isFalse();
        assertThat(SyncUtils.isFarFutureEvent(offset, maxAcceptable, PENDING_ROUND))
                .isFalse();
        assertThat(SyncUtils.isFarFutureEvent(offset, maxAcceptable + 1, PENDING_ROUND))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, -5, Long.MIN_VALUE})
    void negativeOffsetMeansNoEventIsFarFuture(final long offset) {
        assertThat(SyncUtils.isFarFutureEvent(offset, PENDING_ROUND + 1, PENDING_ROUND))
                .isFalse();
        assertThat(SyncUtils.isFarFutureEvent(offset, Long.MAX_VALUE, PENDING_ROUND))
                .isFalse();
    }
}
