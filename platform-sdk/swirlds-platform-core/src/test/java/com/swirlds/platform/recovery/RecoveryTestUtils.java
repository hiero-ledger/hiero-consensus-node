// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery;

import static com.swirlds.base.units.UnitConstants.SECONDS_TO_NANOSECONDS;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static org.hiero.base.CompareTo.isLessThan;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomSignature;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.io.extendable.ExtendableInputStream;
import com.swirlds.common.io.extendable.extensions.CountingStreamExtension;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.event.stream.DefaultConsensusEventStream;
import com.swirlds.platform.recovery.internal.ObjectStreamIterator;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.base.io.SelfSerializable;
import org.hiero.consensus.config.EventConfig_;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.mockito.Mockito;

/**
 * Utilities for recovery tests.
 */
public final class RecoveryTestUtils {

    private RecoveryTestUtils() {}

    /**
     * Generate a random event. Fields inside event are filled mostly with random nonsense data, and a little realistic
     * data (i.e. the event's round).
     *
     * @param random a source of randomness
     * @return an event
     */
    public static CesEvent generateRandomEvent(
            final Random random, final long round, final boolean lastInRound, final Instant now) {

        final PlatformEvent platformEvent = new TestingEventBuilder(random)
                .setAppTransactionCount(random.nextInt(10))
                .setTransactionSize(random.nextInt(10) + 1)
                .setSystemTransactionCount(0)
                .setSelfParent(new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(random.nextLong(0, Long.MAX_VALUE)))
                        .build())
                .setOtherParent(new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(random.nextLong(0, Long.MAX_VALUE)))
                        .build())
                .setTimeCreated(now)
                .setConsensusTimestamp(now)
                .build();

        return new CesEvent(platformEvent, round, lastInRound);
    }

    /**
     * Generate a list of random events.
     *
     * @param random          a source of randomness
     * @param firstRound      the round of the first event
     * @param timeToSimulate  the length of time that should be simulated
     * @param roundsPerSecond the number of rounds per second
     * @param evensPerRound   the number of events in each round
     * @return a list of events
     */
    public static List<CesEvent> generateRandomEvents(
            final Random random,
            final long firstRound,
            final Duration timeToSimulate,
            final int roundsPerSecond,
            final int evensPerRound) {

        final List<CesEvent> events = new ArrayList<>();

        final FakeTime time = new FakeTime();
        final Instant stopTime = time.now().plus(timeToSimulate);
        final Duration timeBetweenEvents =
                Duration.ofNanos((long) (1.0 * SECONDS_TO_NANOSECONDS / roundsPerSecond / evensPerRound));

        long round = firstRound;
        long eventsInRound = 0;

        while (isLessThan(time.now(), stopTime)) {

            final long currentRound = round;
            final Instant now = time.now();
            final boolean lastInRound;

            time.tick(timeBetweenEvents);
            eventsInRound++;
            if (eventsInRound >= evensPerRound) {
                round++;
                eventsInRound = 0;
                lastInRound = true;
            } else {
                lastInRound = false;
            }

            events.add(generateRandomEvent(random, currentRound, lastInRound, now));
        }

        return events;
    }

    /**
     * Write a list of events to event stream files.
     *
     * @param random         a source of randomness (for generating signatures)
     * @param destination    the directory where the files should be written
     * @param secondsPerFile the number of seconds of data in each file
     * @param events         a list of events to be written
     */
    public static void writeRandomEventStream(
            final Random random, final Path destination, final int secondsPerFile, final List<CesEvent> events)
            throws IOException {

        final Configuration configuration = new TestConfigBuilder()
                .withValue(EventConfig_.ENABLE_EVENT_STREAMING, true)
                .withValue(EventConfig_.EVENTS_LOG_DIR, destination.toString())
                .withValue(EventConfig_.EVENTS_LOG_PERIOD, secondsPerFile)
                .getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        final DefaultConsensusEventStream eventStreamManager = new DefaultConsensusEventStream(
                platformContext, NodeId.of(0L), x -> randomSignature(random), "test", x -> false);

        // The event stream writer has flaky asynchronous behavior,
        // so we need to be extra careful when waiting for it to finish.
        // Wrap events and count the number of times they are serialized.
        final AtomicInteger writeCount = new AtomicInteger(0);

        final List<CesEvent> wrappedEvents = new ArrayList<>(events.size());
        for (final CesEvent event : events) {
            final CesEvent wrappedEvent = spy(event);

            Mockito.doAnswer(invocation -> {
                        invocation.callRealMethod();
                        writeCount.incrementAndGet();
                        return null;
                    })
                    .when(wrappedEvent)
                    .serialize(any());

            wrappedEvents.add(wrappedEvent);
        }

        eventStreamManager.addEvents(wrappedEvents);

        // Each event will be serialized twice. Once when it is hashed, and once when it is written to disk.
        assertEventuallyTrue(
                () -> writeCount.get() == events.size() * 2,
                Duration.ofSeconds(10),
                "event not serialized fast enough");

        eventStreamManager.stop();
    }

    /**
     * Compare two event stream files based on creation date.
     */
    private static int compareEventStreamPaths(final Path pathA, final Path pathB) {
        // A nice property of dates is that they are naturally alphabetized by timestamp
        return pathA.getFileName().compareTo(pathB.getFileName());
    }

    /**
     * Check if a file is an event stream file.
     */
    private static boolean isFileAnEventStreamFile(final Path path) {
        return path.toString().endsWith(".evts");
    }

    /**
     * Get the first event stream file from a directory.
     */
    public static Path getFirstEventStreamFile(final Path directory) throws IOException {
        final List<Path> eventStreamFiles = new ArrayList<>();
        Files.walk(directory)
                .filter(RecoveryTestUtils::isFileAnEventStreamFile)
                .sorted(RecoveryTestUtils::compareEventStreamPaths)
                .forEachOrdered(eventStreamFiles::add);

        return eventStreamFiles.get(0);
    }

    /**
     * Get the middle event stream file from a directory.
     */
    public static Path getMiddleEventStreamFile(final Path directory) throws IOException {
        final List<Path> eventStreamFiles = new ArrayList<>();
        Files.walk(directory)
                .filter(RecoveryTestUtils::isFileAnEventStreamFile)
                .sorted(RecoveryTestUtils::compareEventStreamPaths)
                .forEachOrdered(eventStreamFiles::add);

        return eventStreamFiles.get(eventStreamFiles.size() / 2);
    }

    /**
     * Get the last event stream file from a directory.
     */
    public static Path getLastEventStreamFile(final Path directory) throws IOException {
        final List<Path> eventStreamFiles = new ArrayList<>();
        Files.walk(directory)
                .filter(RecoveryTestUtils::isFileAnEventStreamFile)
                .sorted(RecoveryTestUtils::compareEventStreamPaths)
                .forEachOrdered(eventStreamFiles::add);

        return eventStreamFiles.get(eventStreamFiles.size() - 1);
    }

    /**
     * Remove the second half of a file. Updates the file on disk.
     *
     * @param file                     the file to truncate
     * @param truncateOnObjectBoundary if true then truncate the file on an exact object boundary, if false then
     *                                 truncate the file somewhere that isn't an object boundary
     * @return the number of valid objects in the truncated file
     */
    public static int truncateFile(final Path file, boolean truncateOnObjectBoundary) throws IOException {

        // Grab the raw bytes.
        final InputStream in = new BufferedInputStream(new FileInputStream(file.toFile()));
        final byte[] bytes = in.readAllBytes();
        in.close();

        // Read objects from the stream, and count the bytes at each object boundary.
        final Map<Integer, Integer> byteBoundaries = new HashMap<>();
        final CountingStreamExtension counter = new CountingStreamExtension();
        final ExtendableInputStream countingIn = new ExtendableInputStream(new FileInputStream(file.toFile()), counter);
        final IOIterator<SelfSerializable> iterator = new ObjectStreamIterator<>(countingIn, false);
        int count = 0;
        while (iterator.hasNext()) {
            byteBoundaries.put(count, (int) counter.getCount());
            iterator.next();
            count++;
        }
        iterator.close();

        Files.delete(file);

        final int objectIndex = count / 2;
        final int truncationIndex =
                truncateOnObjectBoundary ? byteBoundaries.get(objectIndex) : byteBoundaries.get(objectIndex) - 1;

        final byte[] truncatedBytes = new byte[truncationIndex];
        for (int i = 0; i < truncatedBytes.length; i++) {
            truncatedBytes[i] = bytes[i];
        }

        final OutputStream out = new BufferedOutputStream(new FileOutputStream(file.toFile()));
        out.write(truncatedBytes);
        out.close();

        return objectIndex + (truncateOnObjectBoundary ? 1 : 0);
    }
}
