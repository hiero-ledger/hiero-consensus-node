// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.components;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.internal.ConsensusRound;
import com.swirlds.platform.test.fixtures.event.TestingEventBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Utility functions for testing system transaction handling
 */
public final class TransactionHandlingTestUtils {
    private TransactionHandlingTestUtils() {}

    /**
     * Generate a new bare-bones consensus round, containing DummySystemTransactions
     *
     * @param eventCount           the number of events to include in the round
     * @param transactionsPerEvent the number of transactions to include in each event
     * @return a bare-bones consensus round
     */
    public static ConsensusRound newDummyRound(
            final Random random, final int eventCount, final int transactionsPerEvent) {
        final ConsensusRound round = mock(ConsensusRound.class);

        final List<PlatformEvent> events = new ArrayList<>();
        for (int index = 0; index < eventCount; index++) {
            events.add(new TestingEventBuilder(random)
                    .setSystemTransactionCount(transactionsPerEvent)
                    .build());
        }

        when(round.getConsensusEvents()).thenReturn(events);

        return round;
    }
}
