// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.internal.ConsensusRound;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.Objects;

public class RoundInternalEqualityValidation implements ConsensusRoundValidation {

    @Override
    public void validate(@NonNull final ConsensusRound firstRound, @NonNull final ConsensusRound secondRound) {
        final long firstRoundNumber = firstRound.getRoundNum();
        final long secondRoundNumber = secondRound.getRoundNum();
        assertEquals(
                firstRound.getRoundNum(),
                secondRound.getRoundNum(),
                String.format("round diff at rounds with numbers %d and %d", firstRoundNumber, secondRoundNumber));
        assertEquals(
                firstRound.getEventCount(),
                secondRound.getEventCount(),
                String.format(
                        "event number diff at rounds with numbers %d and %d", firstRoundNumber, secondRoundNumber));
        assertEquals(
                firstRound.getSnapshot(),
                secondRound.getSnapshot(),
                String.format("snapshot diff at rounds with numbers %d and %d", firstRoundNumber, secondRoundNumber));
        final Iterator<PlatformEvent> evIt1 = firstRound.getConsensusEvents().iterator();
        final Iterator<PlatformEvent> evIt2 = secondRound.getConsensusEvents().iterator();
        int eventIndex = 0;
        while (evIt1.hasNext() && evIt2.hasNext()) {
            final PlatformEvent e1 = evIt1.next();
            final PlatformEvent e2 = evIt2.next();
            assertNotNull(
                    e1.getConsensusData(),
                    String.format(
                            "output:1, roundNumberFromFirstNode:%d, roundNumberFromSecondRound:%d, eventIndex%d is not consensus",
                            firstRoundNumber, secondRoundNumber, eventIndex));
            assertNotNull(
                    e2.getConsensusData(),
                    String.format(
                            "output:1, roundNumberFromFirstNode:%d, roundNumberFromSecondRound:%d, eventIndex%d is not consensus",
                            firstRoundNumber, secondRoundNumber, eventIndex));
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
        checkGeneration(event1, event2, sb);
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

    private static void checkGeneration(
            final PlatformEvent event1, final PlatformEvent event2, final StringBuilder sb) {
        if (event1.getGeneration() != event2.getGeneration()) {
            sb.append("   generation mismatch: ")
                    .append(event1.getGeneration())
                    .append(" vs ")
                    .append(event2.getGeneration())
                    .append("\n");
        }
    }
}
