// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus;

import static org.hiero.consensus.model.hashgraph.ConsensusConstants.MIN_TRANS_TIMESTAMP_INCR_NANOS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Random;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventUtilsTest {

    private static final Instant EVENT_TIMESTAMP = Instant.ofEpochSecond(1_000_000L);
    private static final long OFFSET = 104L;

    private PlatformEvent buildEvent(final int appTransactionCount) {
        return new TestingEventBuilder(new Random())
                .setConsensusTimestamp(EVENT_TIMESTAMP)
                .setConsensusOrder(1L)
                .setAppTransactionCount(appTransactionCount)
                .setSystemTransactionCount(0)
                .build();
    }

    @Test
    @DisplayName("Transaction timestamps include userTxnOffsetNanos offset")
    void transactionTimestampOffsetTest() {
        final PlatformEvent event = buildEvent(2);

        assertEquals(
                EVENT_TIMESTAMP.plusNanos(OFFSET),
                event.getTransactionTime(0, OFFSET),
                "transaction 0 should be at eventTimestamp + offset");
        assertEquals(
                EVENT_TIMESTAMP.plusNanos(OFFSET + MIN_TRANS_TIMESTAMP_INCR_NANOS),
                event.getTransactionTime(1, OFFSET),
                "transaction 1 should be at eventTimestamp + offset + 1000ns");
        assertEquals(
                EVENT_TIMESTAMP,
                event.getTransactionTime(0, 0L),
                "with offset=0, transaction 0 should be exactly at eventTimestamp");
    }

    @Test
    @DisplayName("getLastTransTime returns event timestamp for zero transactions")
    void getLastTransTimeZeroTransactionsTest() {
        final PlatformEvent event = buildEvent(0);

        assertEquals(
                EVENT_TIMESTAMP,
                EventUtils.getLastTransTime(event, OFFSET),
                "0 transactions should return raw event timestamp regardless of offset");
    }

    @Test
    @DisplayName("getLastTransTime returns offset timestamp for single transaction")
    void getLastTransTimeOneTransactionTest() {
        final PlatformEvent event = buildEvent(1);

        assertEquals(
                EVENT_TIMESTAMP.plusNanos(OFFSET),
                EventUtils.getLastTransTime(event, OFFSET),
                "1 transaction should return eventTimestamp + offset, not raw eventTimestamp");
        assertEquals(
                EVENT_TIMESTAMP,
                EventUtils.getLastTransTime(event, 0L),
                "with offset=0, should return eventTimestamp");
    }

    @Test
    @DisplayName("getLastTransTime returns last transaction timestamp for multiple transactions")
    void getLastTransTimeMultipleTransactionsTest() {
        final PlatformEvent event = buildEvent(3);

        assertEquals(
                EVENT_TIMESTAMP.plusNanos(OFFSET + 2 * MIN_TRANS_TIMESTAMP_INCR_NANOS),
                EventUtils.getLastTransTime(event, OFFSET),
                "last transaction (index 2) should be at eventTimestamp + offset + 2000ns");
    }
}
