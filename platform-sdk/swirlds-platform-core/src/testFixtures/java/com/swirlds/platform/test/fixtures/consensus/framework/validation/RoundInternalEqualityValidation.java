// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.Objects;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * A validator that ensures that the internal state of two rounds from different nodes are equal.
 */
public enum RoundInternalEqualityValidation implements ConsensusRoundComparisonValidation {
    INSTANCE;

    /**
     * Validates that the internal state of two rounds from different nodes are equal.
     *
     * @param round1 a given node's round be validated
     * @param round2 the corresponding round from another node to be validated
     */
    @Override
    public void validate(@NonNull final ConsensusRound round1, @NonNull final ConsensusRound round2) {
        final long firstRoundNumber = round1.getRoundNum();
        final long secondRoundNumber = round2.getRoundNum();
        assertThat(round1.getRoundNum())
                .withFailMessage(String.format(
                        "round diff at rounds with numbers %d and %d", firstRoundNumber, secondRoundNumber))
                .isEqualTo(round2.getRoundNum());
        assertThat(round1.getEventCount())
                .withFailMessage(String.format(
                        "event number diff at rounds with numbers %d and %d", firstRoundNumber, secondRoundNumber))
                .isEqualTo(round2.getEventCount());
        assertThat(round1.getSnapshot())
                .withFailMessage(String.format(
                        "snapshot diff at rounds with numbers %d and %d", firstRoundNumber, secondRoundNumber))
                .isEqualTo(round2.getSnapshot());
        final Iterator<PlatformEvent> evIt1 = round1.getConsensusEvents().iterator();
        final Iterator<PlatformEvent> evIt2 = round2.getConsensusEvents().iterator();
        int eventIndex = 0;
        while (evIt1.hasNext() && evIt2.hasNext()) {
            final PlatformEvent e1 = evIt1.next();
            final PlatformEvent e2 = evIt2.next();
            assertThat(e1.getConsensusData())
                    .withFailMessage(String.format(
                            "output:1, roundNumberFromFirstNode:%d, roundNumberFromSecondRound:%d, eventIndex%d is not consensus",
                            firstRoundNumber, secondRoundNumber, eventIndex))
                    .isNotNull();
            assertThat(e2.getConsensusData())
                    .withFailMessage(String.format(
                            "output:1, roundNumberFromFirstNode:%d, roundNumberFromSecondRound:%d, eventIndex%d is not consensus",
                            firstRoundNumber, secondRoundNumber, eventIndex))
                    .isNotNull();
            assertConsensusEvents(
                    String.format(
                            "roundNumberFromFirstNode:%d, roundNumberFromSecondRound:%d, event index %d",
                            firstRoundNumber, secondRoundNumber, eventIndex),
                    e1,
                    e2);
            eventIndex++;
        }
    }

    /**
     * Assert that two events are equal. If they are not equal then cause the test to fail and print
     * a meaningful error message.
     *
     * @param description a string that is printed if the events are unequal
     * @param e1 the first event
     * @param e2 the second event
     */
    private static void assertConsensusEvents(
            final String description, final PlatformEvent e1, final PlatformEvent e2) {
        final boolean equal = Objects.equals(e1, e2);
        if (!equal) {
            final StringBuilder sb = new StringBuilder();
            sb.append(description).append("\n");
            sb.append("Events are not equal:\n");
            sb.append("Event 1: ").append(e1).append("\n");
            sb.append("Event 2: ").append(e2).append("\n");
            getEventDifference(sb, e1, e2);
            throw new RuntimeException(sb.toString());
        }
    }

    /** Add a description to a string builder as to why two events are different. */
    private static void getEventDifference(
            final StringBuilder sb, final PlatformEvent event1, final PlatformEvent event2) {
        checkConsensusTimestamp(event1, event2, sb);
        checkConsensusOrder(event1, event2, sb);
    }

    private static void checkConsensusOrder(
            final PlatformEvent event1, final PlatformEvent event2, final StringBuilder sb) {
        if (event1.getConsensusOrder() != event2.getConsensusOrder()) {
            sb.append("   consensus order mismatch: ")
                    .append(event1.getConsensusOrder())
                    .append(" vs ")
                    .append(event2.getConsensusOrder())
                    .append("\n");
        }
    }

    private static void checkConsensusTimestamp(
            final PlatformEvent event1, final PlatformEvent event2, final StringBuilder sb) {
        if (!Objects.equals(event1.getConsensusTimestamp(), event2.getConsensusTimestamp())) {
            sb.append("   consensus timestamp mismatch: ")
                    .append(event1.getConsensusTimestamp())
                    .append(" vs ")
                    .append(event2.getConsensusTimestamp())
                    .append("\n");
        }
    }
}
