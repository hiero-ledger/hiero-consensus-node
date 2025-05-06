// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery;

import static com.swirlds.platform.recovery.RecoveryTestUtils.generateRandomEvents;
import static com.swirlds.platform.recovery.RecoveryTestUtils.writeRandomEventStream;
import static com.swirlds.platform.recovery.internal.EventStreamLowerBound.UNBOUNDED;
import static com.swirlds.platform.test.fixtures.config.ConfigUtils.CONFIGURATION;
import static org.hiero.base.CompareTo.isLessThan;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.platform.recovery.internal.EventStreamLowerBound;
import com.swirlds.platform.recovery.internal.EventStreamPathIterator;
import com.swirlds.platform.recovery.internal.EventStreamRoundLowerBound;
import com.swirlds.platform.recovery.internal.EventStreamTimestampLowerBound;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Iterator;
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

@DisplayName("EventStreamPathIterator Test")
class EventStreamPathIteratorTest {

    @BeforeAll
    static void beforeAll() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds");
        registry.registerConstructables("org.hiero.consensus");
    }

    @Test
    @DisplayName("Starting From First Event Test")
    void startingFromFirstEventTest() throws IOException, NoSuchAlgorithmException {
        final Random random = getRandomPrintSeed();
        final Path directory = LegacyTemporaryFileBuilder.buildTemporaryDirectory(CONFIGURATION);

        final int durationInSeconds = 100;
        final int secondsPerFile = 2;
        final int expectedFileCount = durationInSeconds / secondsPerFile;

        final List<CesEvent> events = generateRandomEvents(random, 1L, Duration.ofSeconds(durationInSeconds), 1, 20);

        writeRandomEventStream(random, directory, secondsPerFile, events);

        final Iterator<Path> iterator = new EventStreamPathIterator(directory, UNBOUNDED);

        final List<Path> files = new ArrayList<>();
        iterator.forEachRemaining(files::add);

        Path prev = null;
        for (final Path path : files) {
            assertTrue(path.toString().endsWith(".evts"), "invalid file extension");
            assertTrue(path.startsWith(directory.toString()), "file not in expected directory");

            if (prev != null) {
                assertTrue(
                        isLessThan(prev.getFileName(), path.getFileName()),
                        "files should be returned in alphabetical order");
            }
            prev = path;
        }

        assertTrue(
                files.size() >= expectedFileCount - 1 && files.size() <= expectedFileCount + 1,
                "unexpected number of files");

        FileUtils.deleteDirectory(directory);
    }

    @Test
    @DisplayName("Starting From Specified Event Test")
    void startingFromSpecifiedEventTest() throws IOException, NoSuchAlgorithmException {
        final Random random = getRandomPrintSeed();
        final Path directory = LegacyTemporaryFileBuilder.buildTemporaryDirectory(CONFIGURATION);

        final int durationInSeconds = 100;
        final int roundsPerSecond = 1;
        final int secondsPerFile = 2;

        final long startingRound = durationInSeconds * roundsPerSecond / 2;
        final int expectedFileCount = durationInSeconds / secondsPerFile / 2;

        final List<CesEvent> events =
                generateRandomEvents(random, 1L, Duration.ofSeconds(durationInSeconds), roundsPerSecond, 20);

        writeRandomEventStream(random, directory, secondsPerFile, events);

        final Iterator<Path> iterator =
                new EventStreamPathIterator(directory, new EventStreamRoundLowerBound(startingRound));

        final List<Path> files = new ArrayList<>();
        iterator.forEachRemaining(files::add);

        Path prev = null;
        for (final Path path : files) {
            assertTrue(path.toString().endsWith(".evts"), "invalid file extension");
            assertTrue(path.startsWith(directory.toString()), "file not in expected directory");

            if (prev != null) {
                assertTrue(
                        isLessThan(prev.getFileName(), path.getFileName()),
                        "files should be returned in alphabetical order");
            }
            prev = path;
        }

        // We are starting roughly in the middle, so make sure we have roughly half the total files
        assertTrue(
                files.size() >= expectedFileCount - 2 && files.size() <= expectedFileCount + 2,
                "unexpected number of files");

        FileUtils.deleteDirectory(directory);
    }

    @Test
    @DisplayName("Request Non Existent Rounds Test")
    void requestNonExistentRoundsTest() throws IOException, NoSuchAlgorithmException {
        final Random random = getRandomPrintSeed();
        final Path directory = LegacyTemporaryFileBuilder.buildTemporaryDirectory(CONFIGURATION);

        final int durationInSeconds = 100;
        final int roundsPerSecond = 1;
        final int secondsPerFile = 2;

        final long firstRound = 100;
        final long requestedRound = 50;

        final List<CesEvent> events =
                generateRandomEvents(random, firstRound, Duration.ofSeconds(durationInSeconds), roundsPerSecond, 20);

        writeRandomEventStream(random, directory, secondsPerFile, events);

        assertThrows(
                NoSuchElementException.class,
                () -> new EventStreamPathIterator(directory, new EventStreamRoundLowerBound(requestedRound)),
                "should not be able to find the file for the given round");

        FileUtils.deleteDirectory(directory);
    }

    @Test
    @DisplayName("Extensive Bound Test")
    void extensiveBoundTest() throws IOException, NoSuchAlgorithmException {
        final Random random = getRandomPrintSeed();
        final Path directory = LegacyTemporaryFileBuilder.buildTemporaryDirectory(CONFIGURATION);

        final int durationInSeconds = 100;
        final int roundsPerSecond = 1;
        final int secondsPerFile = 2;

        final long firstRound = 100;
        final int expectedFileCount = durationInSeconds / secondsPerFile;

        final List<CesEvent> events =
                generateRandomEvents(random, firstRound, Duration.ofSeconds(durationInSeconds), roundsPerSecond, 20);

        writeRandomEventStream(random, directory, secondsPerFile, events);

        final Instant start = events.get(0).getConsensusTimestamp();
        final Instant end = events.get(events.size() - 1).getConsensusTimestamp();

        // unbounded test
        testEventStreamBound(UNBOUNDED, expectedFileCount, directory);

        // round tests
        testEventStreamBound(new EventStreamRoundLowerBound(firstRound), expectedFileCount, directory);
        testEventStreamBound(new EventStreamRoundLowerBound(firstRound + 75), expectedFileCount / 4, directory);
        testEventStreamBound(new EventStreamRoundLowerBound(firstRound + 50), expectedFileCount / 2, directory);
        testEventStreamBound(new EventStreamRoundLowerBound(firstRound + 200), 0, directory);

        // timestamp tests
        final long longStart = start.toEpochMilli();
        final long longEnd = end.toEpochMilli();
        final long longHalfDuration = (longEnd - longStart) / 2;
        final long longQuarterDuration = longHalfDuration / 2;
        TemporalAmount halfDuration = Duration.ofMillis(longHalfDuration);
        TemporalAmount quarterDuration = Duration.ofMillis(longQuarterDuration);
        testEventStreamBound(new EventStreamTimestampLowerBound(start), expectedFileCount, directory);
        testEventStreamBound(
                new EventStreamTimestampLowerBound(start.plus(halfDuration)), expectedFileCount / 2, directory);
        testEventStreamBound(
                new EventStreamTimestampLowerBound(start.plus(halfDuration).plus(quarterDuration)),
                expectedFileCount / 4,
                directory);
        testEventStreamBound(new EventStreamTimestampLowerBound(end.plus(quarterDuration)), 0, directory);

        FileUtils.deleteDirectory(directory);
    }

    /**
     * Test that the iterator returns the correct number of files when given a bound
     *
     * @param lowerBound        the lower bound
     * @param expectedFileCount the expected number of files
     * @param directory         the directory to search
     */
    private void testEventStreamBound(
            @NonNull final EventStreamLowerBound lowerBound, long expectedFileCount, @NonNull final Path directory)
            throws IOException {
        Objects.requireNonNull(lowerBound, "lowerBound must not be null");
        Objects.requireNonNull(directory, "directory must not be null");

        final Iterator<Path> iterator = new EventStreamPathIterator(directory, lowerBound);

        final List<Path> files = new ArrayList<>();
        iterator.forEachRemaining(files::add);

        assertTrue(
                files.size() >= expectedFileCount - 2 && files.size() <= expectedFileCount + 2,
                "unexpected number of files");
    }
}
