// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.report;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.RandomUtils;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.event.report.EventStreamReport;
import com.swirlds.platform.event.report.EventStreamScanner;
import com.swirlds.platform.recovery.internal.EventStreamRoundLowerBound;
import com.swirlds.platform.recovery.internal.EventStreamTimestampLowerBound;
import com.swirlds.platform.test.fixtures.consensus.GenerateConsensus;
import com.swirlds.platform.test.fixtures.simulated.RandomSigner;
import com.swirlds.platform.test.fixtures.stream.StreamUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventStreamReportingToolTest {

    private static final PlatformContext DEFAULT_PLATFORM_CONTEXT =
            TestPlatformContextBuilder.create().build();

    @TempDir
    Path tmpDir;

    /**
     * Generates events, feeds them to consensus, then writes these consensus events to stream files. One the files a
     * written, it generates a report and checks the values.
     */
    @Test
    void createReportTest() throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();
        final int numNodes = 10;
        final int numEvents = 100_000;
        final Duration eventStreamWindowSize = Duration.ofSeconds(1);

        // generate consensus events
        final Deque<ConsensusRound> rounds = GenerateConsensus.generateConsensusRounds(
                DEFAULT_PLATFORM_CONTEXT, numNodes, numEvents, random.nextLong());
        if (rounds.isEmpty()) {
            Assertions.fail("events are excepted to reach consensus");
        }
        // get consensus info
        final long roundToReportFrom = rounds.size() / 2;
        final int numConsensusEvents = rounds.stream()
                .filter(r -> r.getRoundNum() >= roundToReportFrom)
                .mapToInt(ConsensusRound::getNumEvents)
                .sum();
        final List<PlatformEvent> lastRound =
                Optional.ofNullable(rounds.peekLast()).orElseThrow().getConsensusEvents();
        final Instant lastEventTime = lastRound.get(lastRound.size() - 1).getConsensusTimestamp();

        // write event stream
        StreamUtils.writeRoundsToStream(tmpDir, new RandomSigner(random), eventStreamWindowSize, rounds);

        // get report
        final EventStreamReport report = new EventStreamScanner(
                        tmpDir, new EventStreamRoundLowerBound(roundToReportFrom), Duration.ofSeconds(1), false)
                .createReport();

        // assert report has same info as expected
        Assertions.assertEquals(numConsensusEvents, report.summary().eventCount());
        Assertions.assertEquals(lastEventTime, report.summary().end());
        Assertions.assertEquals(
                lastEventTime, report.summary().lastEvent().getPlatformEvent().getConsensusTimestamp());
    }

    /**
     * Generates events, feeds them to consensus, then writes these consensus events to stream files. One the files a
     * written, it generates a report and checks the values.
     */
    @Test
    void createTimeBoundReportTest() throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();
        final int numNodes = 10;
        final int numEvents = 100_000;
        final Duration eventStreamWindowSize = Duration.ofSeconds(1);

        // generate consensus events
        final Deque<ConsensusRound> rounds = GenerateConsensus.generateConsensusRounds(
                DEFAULT_PLATFORM_CONTEXT, numNodes, numEvents, random.nextLong());
        if (rounds.isEmpty()) {
            Assertions.fail("events are excepted to reach consensus");
        }
        // get consensus info
        final long roundToReportFrom = rounds.size() / 2;
        final AtomicReference<Instant> timestampRef = new AtomicReference<>(Instant.MIN);
        final int numConsensusEvents = rounds.stream()
                .filter(r -> {
                    if (r.getRoundNum() >= roundToReportFrom) {
                        timestampRef.compareAndSet(
                                Instant.MIN, r.getConsensusEvents().get(0).getConsensusTimestamp());
                        return true;
                    }
                    return false;
                })
                .mapToInt(ConsensusRound::getNumEvents)
                .sum();
        final List<PlatformEvent> lastRound =
                Optional.ofNullable(rounds.peekLast()).orElseThrow().getConsensusEvents();
        final Instant lastEventTime = lastRound.get(lastRound.size() - 1).getConsensusTimestamp();

        // write event stream
        StreamUtils.writeRoundsToStream(tmpDir, new RandomSigner(random), eventStreamWindowSize, rounds);

        // get report
        final EventStreamReport report = new EventStreamScanner(
                        tmpDir, new EventStreamTimestampLowerBound(timestampRef.get()), Duration.ofSeconds(1), false)
                .createReport();

        // assert report has same info as expected
        Assertions.assertEquals(numConsensusEvents, report.summary().eventCount());
        Assertions.assertEquals(lastEventTime, report.summary().end());
        Assertions.assertEquals(
                lastEventTime, report.summary().lastEvent().getPlatformEvent().getConsensusTimestamp());
    }
}
