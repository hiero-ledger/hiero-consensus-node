// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

public class ConsensusRoundValidation {
    public static void validateConsensusRounds(final ConsensusOutput output1, final ConsensusOutput output2) {
        validateIterableRounds(
                output1.getConsensusRounds().iterator(),
                output2.getConsensusRounds().iterator());

        assertEquals(
                output1.getConsensusRounds().size(),
                output2.getConsensusRounds().size(),
                String.format(
                        "The number of consensus rounds is not the same."
                                + "output1 has %d rounds, output2 has %d rounds",
                        output1.getConsensusRounds().size(),
                        output2.getConsensusRounds().size()));
        validateAncientThresholdIncreases(output1.getConsensusRounds());
    }

    public static void validateAncientThresholdIncreases(@NonNull final List<ConsensusRound> rounds) {
        long lastAncientThreshold = EventConstants.ANCIENT_THRESHOLD_UNDEFINED;
        for (final ConsensusRound round : rounds) {
            final MinimumJudgeInfo thresholdInfo =
                    round.getSnapshot().minimumJudgeInfoList().getLast();
            assertEquals(
                    round.getRoundNum(), thresholdInfo.round(), "the last threshold should be for the current round");
            assertTrue(
                    thresholdInfo.minimumJudgeAncientThreshold() >= lastAncientThreshold,
                    "the ancient threshold should never decrease");
            lastAncientThreshold = thresholdInfo.minimumJudgeAncientThreshold();
        }
    }

    public static void validateIterableRounds(
            final Iterator<ConsensusRound> rndIt1, final Iterator<ConsensusRound> rndIt2) {
        int roundIndex = 0;
        while (rndIt1.hasNext() && rndIt2.hasNext()) {
            final ConsensusRound round1 = rndIt1.next();
            final ConsensusRound round2 = rndIt2.next();

            assertEquals(
                    round1.getRoundNum(),
                    round2.getRoundNum(),
                    String.format("round diff at round index %d", roundIndex));

            assertEquals(
                    round1.getEventCount(),
                    round2.getEventCount(),
                    String.format("event number diff at round index %d", roundIndex));

            assertEquals(
                    round1.getSnapshot(),
                    round2.getSnapshot(),
                    String.format("snapshot diff at round index %d", roundIndex));

            final Iterator<PlatformEvent> evIt1 = round1.getConsensusEvents().iterator();
            final Iterator<PlatformEvent> evIt2 = round2.getConsensusEvents().iterator();

            int eventIndex = 0;
            while (evIt1.hasNext() && evIt2.hasNext()) {
                final PlatformEvent e1 = evIt1.next();
                final PlatformEvent e2 = evIt2.next();

                assertNotNull(
                        e1.getConsensusData(),
                        String.format(
                                "output:1, roundIndex:%d, eventIndex%d is not consensus", roundIndex, eventIndex));
                assertNotNull(
                        e2.getConsensusData(),
                        String.format(
                                "output:1, roundIndex:%d, eventIndex%d is not consensus", roundIndex, eventIndex));

                assertConsensusEvents(String.format("Round index:%d, event index %d", roundIndex, eventIndex), e1, e2);
                eventIndex++;
            }

            roundIndex++;
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
