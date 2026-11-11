// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.shadowgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link SyncUtils#isFarFutureEvent}: an event is a far future event if its birth round is greater than the
 * pending round plus {@code gossip.farFutureEventOffset}. Whether the filter is enabled is decided by the callers, and
 * is covered by their tests.
 */
class SyncUtilsFarFutureTest {

    private static final long PENDING_ROUND = 11;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 5, 10})
    void boundaryIsPendingRoundPlusOffset(final int offset) {
        final long maxAcceptable = PENDING_ROUND + offset;

        assertThat(SyncUtils.isFarFutureEvent(offset, 1, PENDING_ROUND)).isFalse();
        assertThat(SyncUtils.isFarFutureEvent(offset, PENDING_ROUND, PENDING_ROUND))
                .isFalse();
        assertThat(SyncUtils.isFarFutureEvent(offset, maxAcceptable, PENDING_ROUND))
                .isFalse();
        assertThat(SyncUtils.isFarFutureEvent(offset, maxAcceptable + 1, PENDING_ROUND))
                .isTrue();
    }
}
