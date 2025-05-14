// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.consistency;

import static org.hiero.base.utility.ByteUtils.longToByteArray;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.test.fixtures.Randotron;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link ConsistencyTestingToolRound}
 */
class ConsistencyTestingToolRoundTests {
    private static final long ROUND_NUMBER = 22L;
    private static final long STATE_VALUE = 33L;
    private static final List<Long> TRANSACTION_CONTENTS = List.of(4L, 55L, 667L);

    private final ConsistencyTestingToolRound testRound =
            new ConsistencyTestingToolRound(ROUND_NUMBER, STATE_VALUE, TRANSACTION_CONTENTS);

    /**
     * Build a mock round with specified event contents and round received
     *
     * @param eventContents a list of lists, where each individual list represents the transactions in an event in the
     *                      output round
     * @param roundReceived the round received of the mock round
     * @return a mock round with the specified event contents and round received
     */
    private static Round buildMockRound(final List<List<Long>> eventContents, final long roundReceived) {
        final Randotron randotron = Randotron.create();
        final List<PlatformEvent> mockEvents = new ArrayList<>();

        eventContents.forEach(eventContent -> {
            final List<Bytes> transactions = new ArrayList<>();

            eventContent.forEach(transactionContent -> {
                final Bytes bytes = Bytes.wrap(longToByteArray(transactionContent));
                transactions.add(bytes);
            });

            final PlatformEvent e = new TestingEventBuilder(randotron)
                    .setTransactionBytes(transactions)
                    .build();
            mockEvents.add(e);
        });
        final ConsensusSnapshot mockSnapshot = mock(ConsensusSnapshot.class);
        Mockito.when(mockSnapshot.round()).thenReturn(roundReceived);

        return new ConsensusRound(
                mock(Roster.class), mockEvents, mock(EventWindow.class), mockSnapshot, false, Instant.now());
    }

    @Test
    @DisplayName("toString test")
    void toStringTest() {
        final String exampleRoundString = "Round Number: " + ROUND_NUMBER + "; Current State: " + STATE_VALUE
                + "; Transactions: " + TRANSACTION_CONTENTS + "\n";

        assertEquals(exampleRoundString, testRound.toString());
    }

    @Test
    @DisplayName("toString followed by fromString yields equivalent object")
    void toStringRoundTrip() {
        assertEquals(testRound, ConsistencyTestingToolRound.fromString(testRound.toString()));
    }

    @Test
    @DisplayName("fromRound")
    void fromRound() {
        // build a mock round that should equal our test round when converted
        // the transactions from the test round are split across 2 events in this mock round
        final Round equivalentRound = buildMockRound(
                List.of(TRANSACTION_CONTENTS.subList(0, 2), TRANSACTION_CONTENTS.subList(2, 3)), ROUND_NUMBER);

        assertEquals(testRound, ConsistencyTestingToolRound.fromRound(equivalentRound, STATE_VALUE));
    }

    @Test
    @DisplayName("equals")
    void equalsTests() {
        assertFalse(testRound.equals(null), "Round should not equal null");
        assertNotEquals(testRound, new Object(), "Round shouldn't equal an object of a different type");
        assertEquals(testRound, testRound, "Round should equal itself");

        assertNotEquals(
                testRound,
                new ConsistencyTestingToolRound(ROUND_NUMBER - 1, STATE_VALUE, testRound.transactionsContents()),
                "Different round number should cause inequality");

        assertNotEquals(
                testRound,
                new ConsistencyTestingToolRound(ROUND_NUMBER, STATE_VALUE - 1, testRound.transactionsContents()),
                "Different state value should cause inequality");

        final List<Long> differentTransactions = new ArrayList<>(testRound.transactionsContents());
        differentTransactions.add(123L);
        assertNotEquals(
                testRound,
                new ConsistencyTestingToolRound(ROUND_NUMBER, STATE_VALUE, differentTransactions),
                "Different transactions should cause inequality");
    }

    @Test
    @DisplayName("compareTo")
    void compareToTests() {
        final ConsistencyTestingToolRound otherRound =
                new ConsistencyTestingToolRound(ROUND_NUMBER + 1, STATE_VALUE, TRANSACTION_CONTENTS);

        assertEquals(0, testRound.compareTo(testRound));
        assertEquals(-1, testRound.compareTo(otherRound));
        assertEquals(1, otherRound.compareTo(testRound));
    }
}
