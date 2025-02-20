// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.test.fixtures.LoggingMirror;
import com.swirlds.logging.test.fixtures.WithLoggingMirror;
import com.swirlds.platform.consensus.ConsensusSnapshot;
import com.swirlds.platform.consensus.EventWindow;
import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.event.preconsensus.durability.DefaultRoundDurabilityBuffer;
import com.swirlds.platform.event.preconsensus.durability.RoundDurabilityBuffer;
import com.swirlds.platform.internal.ConsensusRound;
import com.swirlds.platform.test.fixtures.event.TestingEventBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@WithLoggingMirror
class RoundDurabilityBufferTests {

    // Round added before durability
    // round added after durability
    // lots of rounds added and all made durable all at once
    // lots of rounds added and some made durable all at once

    @Inject
    LoggingMirror loggingMirror;

    /**
     * Creates a round with a specific round number and keystone event
     *
     * @param randotron              a source of randomness
     * @param keystoneSequenceNumber the sequence number of the keystone event
     * @return a round with the specified data
     */
    @NonNull
    private static ConsensusRound buildMockRound(@NonNull final Random randotron, final long keystoneSequenceNumber) {

        final PlatformEvent keystoneEvent = new TestingEventBuilder(randotron).build();
        keystoneEvent.setStreamSequenceNumber(keystoneSequenceNumber);

        return new ConsensusRound(
                mock(Roster.class),
                List.of(),
                keystoneEvent,
                mock(EventWindow.class),
                mock(ConsensusSnapshot.class),
                false,
                Instant.now());
    }

    @Test
    void decreasingSequenceNumberIsRejectedTest() {
        final Random randotron = getRandomPrintSeed();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final RoundDurabilityBuffer roundDurabilityBuffer = new DefaultRoundDurabilityBuffer(platformContext);

        final long sequenceNumber = randotron.nextLong(1, 1000);
        roundDurabilityBuffer.setLatestDurableSequenceNumber(sequenceNumber);
        assertThrows(
                IllegalArgumentException.class,
                () -> roundDurabilityBuffer.setLatestDurableSequenceNumber(sequenceNumber - 1));
    }

    @Test
    void roundsAddedBeforeTheyAreDurableTest() {
        final Random randotron = getRandomPrintSeed();

        final long roundCount = 100;

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final RoundDurabilityBuffer roundDurabilityBuffer = new DefaultRoundDurabilityBuffer(platformContext);

        final List<ConsensusRound> rounds = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final ConsensusRound round = buildMockRound(randotron, i);
            rounds.add(round);
            final List<ConsensusRound> result = roundDurabilityBuffer.addRound(round);
            assertTrue(result.isEmpty());
        }

        // This method just logs. The least we can do is make sure it doesn't throw any exceptions.
        roundDurabilityBuffer.checkForStaleRounds(Instant.now());

        // Slowly increase the durable sequence number a little at a time. Verify that the rounds are returned in
        // the proper order and at the proper time.
        final List<ConsensusRound> durableRounds = new ArrayList<>();
        long durableSequenceNumber = -1;
        while (durableSequenceNumber < roundCount) {
            durableSequenceNumber += randotron.nextInt(1, 10);
            final List<ConsensusRound> result =
                    roundDurabilityBuffer.setLatestDurableSequenceNumber(durableSequenceNumber);
            durableRounds.addAll(result);

            assertEquals(durableRounds.size(), Math.min(durableSequenceNumber + 1, roundCount));
            for (int i = 0; i < Math.min(durableSequenceNumber + 1, roundCount); i++) {
                assertSame(durableRounds.get(i), rounds.get(i));
            }
        }

        // Should also not throw when there are no rounds in the buffer.
        roundDurabilityBuffer.checkForStaleRounds(Instant.now());
    }

    @Test
    void roundsAddedAfterTheyAreDurableTest() {
        final Random randotron = getRandomPrintSeed();

        final long roundCount = 100;

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final RoundDurabilityBuffer roundDurabilityBuffer = new DefaultRoundDurabilityBuffer(platformContext);

        roundDurabilityBuffer.setLatestDurableSequenceNumber(roundCount + randotron.nextInt(1, 10));

        for (int i = 0; i < 100; i++) {
            final ConsensusRound round = buildMockRound(randotron, i);
            final List<ConsensusRound> result = roundDurabilityBuffer.addRound(round);
            assertEquals(result.size(), 1);
            assertSame(result.getFirst(), round);
        }
    }

    @Test
    void clearTest() {
        final Random randotron = getRandomPrintSeed();

        final long roundCount = 100;

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final RoundDurabilityBuffer roundDurabilityBuffer = new DefaultRoundDurabilityBuffer(platformContext);

        final List<ConsensusRound> rounds = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final ConsensusRound round = buildMockRound(randotron, i);
            rounds.add(round);
            final List<ConsensusRound> result = roundDurabilityBuffer.addRound(round);
            assertTrue(result.isEmpty());
        }

        // This method just logs. The least we can do is make sure it doesn't throw any exceptions.
        roundDurabilityBuffer.checkForStaleRounds(Instant.now());

        // Slowly increase the durable sequence number a little at a time. Verify that the rounds are returned in
        // the proper order and at the proper time.
        final List<ConsensusRound> durableRounds = new ArrayList<>();
        long durableSequenceNumber = -1;
        boolean cleared = false;
        while (durableSequenceNumber < roundCount) {
            durableSequenceNumber += randotron.nextInt(1, 10);
            final List<ConsensusRound> result =
                    roundDurabilityBuffer.setLatestDurableSequenceNumber(durableSequenceNumber);
            durableRounds.addAll(result);

            if (durableSequenceNumber > roundCount / 2) {
                if (!cleared) {
                    roundDurabilityBuffer.clear();
                    cleared = true;
                }
                assertTrue(roundDurabilityBuffer
                        .setLatestDurableSequenceNumber(durableSequenceNumber)
                        .isEmpty());
            } else {
                assertEquals(durableRounds.size(), Math.min(durableSequenceNumber + 1, roundCount));
                for (int i = 0; i < Math.min(durableSequenceNumber + 1, roundCount); i++) {
                    assertSame(durableRounds.get(i), rounds.get(i));
                }
            }
        }

        // Should also not throw when there are no rounds in the buffer.
        roundDurabilityBuffer.checkForStaleRounds(Instant.now());
    }

    @Disabled // FUTURE WORK: enable once we switch to new logging API
    @Test
    void staleRoundTest() {
        final Random randotron = getRandomPrintSeed();

        final FakeTime time = new FakeTime();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().withTime(time).build();
        final RoundDurabilityBuffer roundDurabilityBuffer = new DefaultRoundDurabilityBuffer(platformContext);

        // Add a round that will sit around for a long time
        final ConsensusRound round = buildMockRound(randotron, 1234);

        final List<ConsensusRound> results = roundDurabilityBuffer.addRound(round);
        assertTrue(results.isEmpty());

        // Should not log.
        roundDurabilityBuffer.checkForStaleRounds(time.now());
        assertEquals(0, loggingMirror.getEvents().size());

        final Duration suspicousDuration = platformContext
                .getConfiguration()
                .getConfigData(PcesConfig.class)
                .suspiciousRoundDurabilityDuration();

        // Should not log.
        time.tick(suspicousDuration.minusSeconds(1));
        roundDurabilityBuffer.checkForStaleRounds(time.now());
        assertEquals(0, loggingMirror.getEvents().size());

        // Should finally log.
        time.tick(Duration.ofSeconds(2));
        roundDurabilityBuffer.checkForStaleRounds(time.now());
        assertEquals(1, loggingMirror.getEvents().size());
        final LogEvent logMessage = loggingMirror.getEvents().getFirst();
        assertEquals(Level.ERROR, logMessage.level());

        // Logger should currently be rate limited, should not log.
        time.tick(Duration.ofSeconds(1));
        roundDurabilityBuffer.checkForStaleRounds(time.now());
        assertEquals(1, loggingMirror.getEvents().size());

        // Should be able to log again after a long time passes.
        time.tick(Duration.ofMinutes(100));
        roundDurabilityBuffer.checkForStaleRounds(time.now());
        assertEquals(2, loggingMirror.getEvents().size());
    }
}
