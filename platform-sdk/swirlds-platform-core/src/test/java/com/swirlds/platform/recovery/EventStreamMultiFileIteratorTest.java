// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery;

import static com.swirlds.platform.recovery.RecoveryTestUtils.generateRandomEvents;
import static com.swirlds.platform.recovery.RecoveryTestUtils.getLastEventStreamFile;
import static com.swirlds.platform.recovery.RecoveryTestUtils.getMiddleEventStreamFile;
import static com.swirlds.platform.recovery.RecoveryTestUtils.truncateFile;
import static com.swirlds.platform.recovery.RecoveryTestUtils.writeRandomEventStream;
import static com.swirlds.platform.recovery.internal.EventStreamLowerBound.UNBOUNDED;
import static com.swirlds.platform.test.fixtures.config.ConfigUtils.CONFIGURATION;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.io.IOIterator;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.platform.recovery.internal.EventStreamLowerBound;
import com.swirlds.platform.recovery.internal.EventStreamMultiFileIterator;
import com.swirlds.platform.recovery.internal.EventStreamRoundLowerBound;
import com.swirlds.platform.recovery.internal.EventStreamTimestampLowerBound;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.model.event.CesEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EventStreamMultiFileIterator")
class EventStreamMultiFileIteratorTest {

    @BeforeAll
    static void beforeAll() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds");
        registry.registerConstructables("org.hiero.consensus");
    }

    public static void assertEventsAreEqual(final CesEvent expected, final CesEvent actual) {
        assertEquals(expected.getPlatformEvent(), actual.getPlatformEvent());
        assertEquals(
                expected.getPlatformEvent().getConsensusData(),
                actual.getPlatformEvent().getConsensusData());
    }

    @Test
    @DisplayName("Read All Events Test")
    void readAllEventsTest() throws IOException, NoSuchAlgorithmException {
        final Random random = getRandomPrintSeed();
        final Path directory = LegacyTemporaryFileBuilder.buildTemporaryDirectory(CONFIGURATION);

        final int durationInSeconds = 100;
        final int secondsPerFile = 2;

        final List<CesEvent> events = generateRandomEvents(random, 1L, Duration.ofSeconds(durationInSeconds), 1, 20);

        writeRandomEventStream(random, directory, secondsPerFile, events);

        try (final IOIterator<CesEvent> iterator = new EventStreamMultiFileIterator(directory, UNBOUNDED)) {

            final List<CesEvent> deserializedEvents = new ArrayList<>();
            iterator.forEachRemaining(deserializedEvents::add);

            assertEquals(events.size(), deserializedEvents.size(), "unexpected number of events read");

            for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {

                final CesEvent event = deserializedEvents.get(eventIndex);

                // Convert to event impl to allow comparison
                assertEventsAreEqual(event, events.get(eventIndex));
            }

        } finally {
            FileUtils.deleteDirectory(directory);
        }
    }

    @Test
    @DisplayName("Read Events Starting At Round Test")
    void readEventsStartingAtRoundTest() throws NoSuchAlgorithmException, IOException {
        final Random random = getRandomPrintSeed();
        final Path directory = LegacyTemporaryFileBuilder.buildTemporaryDirectory(CONFIGURATION);

        final int durationInSeconds = 100;
        final int secondsPerFile = 2;

        final List<CesEvent> events = generateRandomEvents(random, 1L, Duration.ofSeconds(durationInSeconds), 1, 20);

        // Figure out which event is the first one we expect to see
        final int readStartingAtRound = 10;
        int startingIndex = 0;
        while (events.get(startingIndex).getRoundReceived() < readStartingAtRound) {
            startingIndex++;
        }

        writeRandomEventStream(random, directory, secondsPerFile, events);

        try (final IOIterator<CesEvent> iterator =
                new EventStreamMultiFileIterator(directory, new EventStreamRoundLowerBound(readStartingAtRound))) {

            final List<CesEvent> deserializedEvents = new ArrayList<>();
            iterator.forEachRemaining(deserializedEvents::add);

            for (int eventIndex = 0; eventIndex < events.size() - startingIndex; eventIndex++) {

                final CesEvent event = deserializedEvents.get(eventIndex);
                assertEventsAreEqual(event, events.get(eventIndex + startingIndex));
            }

        } finally {
            FileUtils.deleteDirectory(directory);
        }
    }

    @Test
    @DisplayName("Read Events Starting At Non-Existent Round Test")
    void readEventsStartingAtNonExistentRoundTest() throws NoSuchAlgorithmException, IOException {

        final Random random = getRandomPrintSeed();
        final Path directory = LegacyTemporaryFileBuilder.buildTemporaryDirectory(CONFIGURATION);

        final int durationInSeconds = 100;
        final int secondsPerFile = 2;
        final long firstRound = 50;

        final List<CesEvent> events =
                generateRandomEvents(random, firstRound, Duration.ofSeconds(durationInSeconds), 1, 20);

        // Figure out which event is the first one we expect to see
        final int readStartingAtRound = 10;
        int startingIndex = 0;
        while (events.get(startingIndex).getRoundReceived() < readStartingAtRound) {
            startingIndex++;
        }

        writeRandomEventStream(random, directory, secondsPerFile, events);

        assertThrows(
                NoSuchElementException.class,
                () -> new EventStreamMultiFileIterator(directory, new EventStreamRoundLowerBound(readStartingAtRound)),
                "should throw if events are not available");

        FileUtils.deleteDirectory(directory);
    }

    @Test
    @DisplayName("Read Events Starting At Non-Existent Round Test")
    void missingEventStreamFileTest() throws IOException, NoSuchAlgorithmException {

        final Random random = getRandomPrintSeed();
        final Path directory = LegacyTemporaryFileBuilder.buildTemporaryDirectory(CONFIGURATION);

        final int durationInSeconds = 100;
        final int secondsPerFile = 2;

        final List<CesEvent> events = generateRandomEvents(random, 1L, Duration.ofSeconds(durationInSeconds), 1, 20);

        writeRandomEventStream(random, directory, secondsPerFile, events);

        final Path fileToDelete = getMiddleEventStreamFile(directory);
        Files.delete(fileToDelete);

        boolean readFailed = false;
        try (final IOIterator<CesEvent> iterator = new EventStreamMultiFileIterator(directory, UNBOUNDED)) {

            final List<CesEvent> deserializedEvents = new ArrayList<>();
            iterator.forEachRemaining(deserializedEvents::add);

            assertEquals(events.size(), deserializedEvents.size(), "unexpected number of events read");

            for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {

                final CesEvent event = deserializedEvents.get(eventIndex);
                assertEquals(event, events.get(eventIndex), "event should match input event");
            }

        } catch (final IOException ioException) {
            readFailed = true;
        } finally {
            FileUtils.deleteDirectory(directory);
        }

        assertTrue(readFailed, "should have been unable to read with missing file");
    }

    @Test
    @DisplayName("Truncate Last File Test")
    void truncatedLastFileTest() throws NoSuchAlgorithmException, IOException {
        final Random random = getRandomPrintSeed();
        final Path directory = LegacyTemporaryFileBuilder.buildTemporaryDirectory(CONFIGURATION);

        final int durationInSeconds = 100;
        final int secondsPerFile = 2;

        final List<CesEvent> events = generateRandomEvents(random, 1L, Duration.ofSeconds(durationInSeconds), 1, 20);

        writeRandomEventStream(random, directory, secondsPerFile, events);

        final Path lastFile = getLastEventStreamFile(directory);
        truncateFile(lastFile, false);

        try (final IOIterator<CesEvent> iterator = new EventStreamMultiFileIterator(directory, UNBOUNDED)) {

            final List<CesEvent> deserializedEvents = new ArrayList<>();

            try {
                iterator.forEachRemaining(deserializedEvents::add);
            } catch (final IOException e) {
                if (e.getMessage().contains("does not contain any events")) {
                    // The last file had too few events and the truncated file had no events.
                    // This happens randomly, but especially when the original file has 3 or less events in it.
                    // abort the unit tests in a successful state.
                    return;
                } else {
                    throw e;
                }
            }

            assertTrue(deserializedEvents.size() > 0, "some events should have been deserialized");
            assertTrue(events.size() > deserializedEvents.size(), "some events should not have been deserialized");

            for (int eventIndex = 0; eventIndex < deserializedEvents.size(); eventIndex++) {

                final CesEvent event = deserializedEvents.get(eventIndex);
                assertEventsAreEqual(event, events.get(eventIndex));
            }

        } finally {
            FileUtils.deleteDirectory(directory);
        }
    }

    @Test
    @DisplayName("Truncate Middle File Test")
    void truncatedMiddleFileTest() throws NoSuchAlgorithmException, IOException {
        final Random random = getRandomPrintSeed();
        final Path directory = LegacyTemporaryFileBuilder.buildTemporaryDirectory(CONFIGURATION);

        final int durationInSeconds = 100;
        final int secondsPerFile = 2;

        final List<CesEvent> events = generateRandomEvents(random, 1L, Duration.ofSeconds(durationInSeconds), 1, 20);

        writeRandomEventStream(random, directory, secondsPerFile, events);

        final Path fileToTruncate = getMiddleEventStreamFile(directory);
        truncateFile(fileToTruncate, false);

        boolean readFailed = false;
        try (final IOIterator<CesEvent> iterator = new EventStreamMultiFileIterator(directory, UNBOUNDED)) {

            final List<CesEvent> deserializedEvents = new ArrayList<>();
            iterator.forEachRemaining(deserializedEvents::add);

            assertEquals(events.size(), deserializedEvents.size(), "unexpected number of events read");

            for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {

                final CesEvent event = deserializedEvents.get(eventIndex);
                assertEquals(event, events.get(eventIndex), "event should match input event");
            }

        } catch (final IOException ioException) {
            readFailed = true;
        } finally {
            FileUtils.deleteDirectory(directory);
        }

        assertTrue(readFailed, "should have been unable to read with missing file");
    }

    @Test
    @DisplayName("Extensive Bound Test")
    void extensiveBoundTest() throws IOException, NoSuchAlgorithmException, ConstructableRegistryException {
        final Random random = getRandomPrintSeed();
        final Path directory = LegacyTemporaryFileBuilder.buildTemporaryDirectory(CONFIGURATION);

        final int durationInSeconds = 100;
        final int roundsPerSecond = 1;
        final int secondsPerFile = 2;

        final long firstRound = 100;

        final List<CesEvent> events =
                generateRandomEvents(random, firstRound, Duration.ofSeconds(durationInSeconds), roundsPerSecond, 20);

        writeRandomEventStream(random, directory, secondsPerFile, events);

        final Instant start = events.get(0).getConsensusTimestamp();
        final Instant end = events.get(events.size() - 1).getConsensusTimestamp();

        // unbounded test
        testEventStreamBound(UNBOUNDED, events, directory);

        // round tests
        testEventStreamBound(new EventStreamRoundLowerBound(firstRound), events, directory);
        testEventStreamBound(new EventStreamRoundLowerBound(firstRound + 75), events, directory);
        testEventStreamBound(new EventStreamRoundLowerBound(firstRound + 50), events, directory);
        testEventStreamBound(new EventStreamRoundLowerBound(firstRound + 200), events, directory);

        // timestamp tests
        final long longStart = start.toEpochMilli();
        final long longEnd = end.toEpochMilli();
        final long longHalfDuration = (longEnd - longStart) / 2;
        final long longQuarterDuration = longHalfDuration / 2;
        TemporalAmount halfDuration = Duration.ofMillis(longHalfDuration);
        TemporalAmount quarterDuration = Duration.ofMillis(longQuarterDuration);
        testEventStreamBound(new EventStreamTimestampLowerBound(start), events, directory);
        testEventStreamBound(new EventStreamTimestampLowerBound(start.plus(halfDuration)), events, directory);
        testEventStreamBound(
                new EventStreamTimestampLowerBound(start.plus(halfDuration).plus(quarterDuration)), events, directory);
        testEventStreamBound(new EventStreamTimestampLowerBound(end.plus(quarterDuration)), events, directory);

        FileUtils.deleteDirectory(directory);
    }

    /**
     * Test that the iterator returns the correct events when given a bound
     *
     * @param lowerBound the lower bound to use
     * @param events     the list of all events
     * @param directory  the directory to search
     */
    private void testEventStreamBound(
            @NonNull final EventStreamLowerBound lowerBound,
            @NonNull final List<CesEvent> events,
            @NonNull final Path directory)
            throws IOException {
        Objects.requireNonNull(lowerBound, "lowerBound must not be null");
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(directory, "directory must not be null");

        int startingIndex = 0;
        while (startingIndex < events.size() && lowerBound.compareTo(events.get(startingIndex)) < 0) {
            startingIndex++;
        }

        try (final IOIterator<CesEvent> iterator = new EventStreamMultiFileIterator(directory, lowerBound)) {

            final List<CesEvent> deserializedEvents = new ArrayList<>();

            try {
                iterator.forEachRemaining(deserializedEvents::add);
            } catch (final IOException e) {
                if (e.getMessage().contains("does not contain any events")) {
                    // The last file had too few events and the truncated file had no events.
                    // This happens randomly, but especially when the original file has 3 or less events in it.
                    // abort the unit tests in a successful state.
                    return;
                } else {
                    throw e;
                }
            }

            for (int eventIndex = 0; eventIndex < deserializedEvents.size(); eventIndex++) {

                final CesEvent event = deserializedEvents.get(eventIndex);
                assertEventsAreEqual(event, events.get(startingIndex + eventIndex));
            }
        }
    }
}
