// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertIteratorEquality;
import static com.swirlds.platform.event.preconsensus.PcesFileManager.NO_LOWER_BOUND;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.common.test.fixtures.platform.TestPlatformContexts;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.test.fixtures.event.PcesWriterTestUtils;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.preconsensus.PcesTestFilesGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import org.hiero.base.CompareTo;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PcesFileManagerTest {

    @TempDir
    private Path tempDir;

    private final int numEvents = 1_000;

    @NonNull
    private static PlatformContext buildContext(@NonNull final Configuration configuration) {
        return TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .withTime(new FakeTime(Duration.ofMillis(1)))
                .build();
    }

    @NonNull
    private PlatformContext getPlatformContext() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(PcesConfig_.DATABASE_DIRECTORY, tempDir.toString())
                .getOrCreateConfig();
        return buildContext(configuration);
    }

    private Random random;

    private static final int NUM_FILES = 100;

    private Path fileDirectory = null;
    private Path dataDirectory = null;

    @BeforeEach
    void beforeEach() {
        dataDirectory = tempDir.resolve("data");
        fileDirectory = dataDirectory.resolve("0");
        random = getRandomPrintSeed();
    }

    @Test
    void standardOperationTest() throws Exception {
        final PlatformContext platformContext = getPlatformContext();
        final Random random = RandomUtils.getRandomPrintSeed();

        final StandardGraphGenerator generator = PcesWriterTestUtils.buildGraphGenerator(platformContext, random);

        final List<PlatformEvent> events = new LinkedList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEventWithoutIndex().getBaseEvent());
        }
        final PcesFileManager writer = new PcesFileManager(platformContext, 0, tempDir);

        writer.beginStreamingNewEvents();
        for (final PlatformEvent event : events) {
            writer.prepareOutputStream(event);
            writer.writeEvent(event);
            writer.sync();
        }
        writer.closeCurrentMutableFile();
        // forces the writer to close the current file so that we can verify the stream
        writer.registerDiscontinuity(1L);

        PcesWriterTestUtils.verifyStream(tempDir, events, platformContext, 0);
    }

    @Test
    void ancientEventTest() throws Exception {

        final Random random = RandomUtils.getRandomPrintSeed();
        final PlatformContext platformContext = getPlatformContext();
        final StandardGraphGenerator generator = PcesWriterTestUtils.buildGraphGenerator(platformContext, random);

        final int stepsUntilAncient = random.nextInt(50, 100);
        final PcesFileManager writer = new PcesFileManager(platformContext, 0, tempDir);

        // We will add this event at the very end, it should be ancient by then
        final PlatformEvent ancientEvent = generator.generateEventWithoutIndex().getBaseEvent();

        final List<PlatformEvent> events = new LinkedList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEventWithoutIndex().getBaseEvent());
        }

        writer.beginStreamingNewEvents();

        long lowerBound = ConsensusConstants.ROUND_FIRST;
        final Iterator<PlatformEvent> iterator = events.iterator();
        while (iterator.hasNext()) {
            final PlatformEvent event = iterator.next();
            writer.prepareOutputStream(event);
            writer.writeEvent(event);
            writer.sync();
            lowerBound = Math.max(lowerBound, event.getBirthRound() - stepsUntilAncient);

            writer.updateNonAncientEventBoundary(EventWindowBuilder.builder()
                    .setAncientThreshold(lowerBound)
                    .setExpiredThreshold(lowerBound)
                    .build());

            if (event.getBirthRound() < lowerBound) {
                // Although it's not common, it's actually possible that the generator will generate
                // an event that is ancient (since it isn't aware of what we consider to be ancient)
                iterator.remove();
            }
        }

        if (lowerBound > ancientEvent.getBirthRound()) {
            // This is probably not possible... but just in case make sure this event is ancient
            try {
                writer.updateNonAncientEventBoundary(EventWindowBuilder.builder()
                        .setAncientThreshold(ancientEvent.getBirthRound() + 1)
                        .setExpiredThreshold(ancientEvent.getBirthRound() + 1)
                        .build());
            } catch (final IllegalArgumentException e) {
                // ignore, more likely than not this event is way older than the actual ancient threshold
            }
        }

        // forces the writer to close the current file so that we can verify the stream
        writer.registerDiscontinuity(1L);

        PcesWriterTestUtils.verifyStream(tempDir, events, platformContext, 0);
    }

    @Test
    @DisplayName("Generate Descriptors With Manager Test")
    void generateDescriptorsWithManagerTest() throws IOException {
        final PlatformContext platformContext = TestPlatformContexts.context(Time.getCurrent(), dataDirectory);

        final var result = PcesTestFilesGenerator.Builder.create(random, fileDirectory)
                .build()
                .generate();

        final PcesFileTracker fileTracker = PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, false);

        final List<PcesFile> expectedFiles = result.files();
        assertIteratorEquality(expectedFiles.iterator(), fileTracker.getFileIterator(NO_LOWER_BOUND, 0));
    }

    @Test
    @DisplayName("Incremental Pruning By Ancient Boundary Test")
    void incrementalPruningByAncientBoundaryTest() throws IOException {
        final var pcesFilesGeneratorResult = PcesTestFilesGenerator.Builder.create(random, fileDirectory)
                .withNumFilesToGenerate(NUM_FILES)
                .build()
                .generate();

        // Choose a file in the middle. The goal is to incrementally purge all files before this file, but not
        // to purge this file or any of the ones after it.
        final int middleFileIndex = NUM_FILES / 2;

        final List<PcesFile> expectedFiles = pcesFilesGeneratorResult.files();
        final PcesFile firstFile = expectedFiles.getFirst();
        final PcesFile middleFile = expectedFiles.get(middleFileIndex);
        final PcesFile lastFile = expectedFiles.getLast();

        // Set the far in the future, we want all files to be GC eligible by temporal reckoning.
        final FakeTime time = new FakeTime(lastFile.getTimestamp().plus(Duration.ofHours(1)), Duration.ZERO);
        final PlatformContext platformContext = TestPlatformContexts.context(time, dataDirectory);

        final PcesFileManager manager = new PcesFileManager(platformContext, 0, fileDirectory);
        manager.beginStreamingNewEvents();
        // assertIteratorEquality(expectedFiles.iterator(), fileTracker.getFileIterator(NO_LOWER_BOUND, 0));

        // Increase the pruned ancient threshold a little at a time,
        // until the middle file is almost GC eligible but not quite.
        for (long ancientThreshold = firstFile.getUpperBound() - 100;
                ancientThreshold <= middleFile.getUpperBound();
                ancientThreshold++) {

            manager.pruneOldFiles(ancientThreshold);

            // Parse files afresh to make sure we aren't "cheating" by just
            // removing the in-memory descriptor without also removing the file on disk
            final PcesFileTracker freshFileTracker =
                    PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, false);

            final PcesFile firstUnPrunedFile = freshFileTracker.getFirstFile();

            int firstUnPrunedIndex = -1;
            for (int index = 0; index < expectedFiles.size(); index++) {
                if (expectedFiles.get(index).equals(firstUnPrunedFile)) {
                    firstUnPrunedIndex = index;
                    break;
                }
            }

            // Check the first file that wasn't pruned
            assertTrue(firstUnPrunedIndex <= middleFileIndex);
            if (firstUnPrunedIndex < middleFileIndex) {
                assertTrue(firstUnPrunedFile.getUpperBound() >= ancientThreshold);
            }

            // Check the file right before the first un-pruned file.
            if (firstUnPrunedIndex > 0) {
                final PcesFile lastPrunedFile = expectedFiles.get(firstUnPrunedIndex - 1);
                assertTrue(lastPrunedFile.getUpperBound() < ancientThreshold);
            }

            // Check all remaining files to make sure we didn't accidentally delete something from the end
            final List<PcesFile> remainingFiles = expectedFiles.subList(firstUnPrunedIndex, NUM_FILES);

            // iterate over all files in the fresh file tracker to make sure they match expected
            assertIteratorEquality(remainingFiles.iterator(), freshFileTracker.getFileIterator(NO_LOWER_BOUND, 0));
        }

        // Now, prune files so that the middle file is no longer needed.
        manager.pruneOldFiles(middleFile.getUpperBound() + 1);

        // Parse files afresh to make sure we aren't "cheating" by just
        // removing the in-memory descriptor without also removing the file on disk
        final PcesFileTracker freshFileTracker =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, false);

        final PcesFile firstUnPrunedFile = freshFileTracker.getFirstFile();

        int firstUnPrunedIndex = -1;
        for (int index = 0; index < expectedFiles.size(); index++) {
            if (expectedFiles.get(index).equals(firstUnPrunedFile)) {
                firstUnPrunedIndex = index;
                break;
            }
        }

        assertEquals(middleFileIndex + 1, firstUnPrunedIndex);
    }

    @Test
    @DisplayName("Incremental Pruning By Timestamp Test")
    void incrementalPruningByTimestampTest() throws IOException {
        final var pcesFilesGeneratorResult = PcesTestFilesGenerator.Builder.create(random, fileDirectory)
                .withNumFilesToGenerate(NUM_FILES)
                .build()
                .generate();
        final var files = pcesFilesGeneratorResult.files();

        // Choose a file in the middle. The goal is to incrementally purge all files before this file, but not
        // to purge this file or any of the ones after it.
        final int middleFileIndex = NUM_FILES / 2;

        final PcesFile firstFile = files.getFirst();
        final PcesFile middleFile = files.get(middleFileIndex);
        final PcesFile lastFile = files.getLast();

        // Set the clock before the first file is not garbage collection eligible
        final FakeTime time = new FakeTime(firstFile.getTimestamp().plus(Duration.ofMinutes(59)), Duration.ZERO);
        final PlatformContext platformContext = TestPlatformContexts.context(time, dataDirectory);

        final PcesFileManager manager = new PcesFileManager(platformContext, 0, fileDirectory);

        manager.beginStreamingNewEvents();
        // assertIteratorEquality(files.iterator(), fileTracker.getFileIterator(NO_LOWER_BOUND, 0));

        // Increase the timestamp a little at a time. We should gradually delete files up until
        // all files before the middle file have been deleted.
        final Instant endingTime =
                middleFile.getTimestamp().plus(Duration.ofMinutes(60).minus(Duration.ofNanos(1)));

        Duration nextTimeIncrease = Duration.ofSeconds(random.nextInt(1, 20));
        while (time.now().plus(nextTimeIncrease).isBefore(endingTime)) {
            time.tick(nextTimeIncrease);
            manager.pruneOldFiles(lastFile.getUpperBound() + 1);

            // Parse files afresh to make sure we aren't "cheating" by just
            // removing the in-memory descriptor without also removing the file on disk
            final PcesFileTracker freshFileTracker =
                    PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, false);

            final PcesFile firstUnPrunedFile = freshFileTracker.getFirstFile();

            int firstUnPrunedIndex = -1;
            for (int index = 0; index < files.size(); index++) {
                if (files.get(index).equals(firstUnPrunedFile)) {
                    firstUnPrunedIndex = index;
                    break;
                }
            }

            // Check the first file that wasn't pruned
            assertTrue(firstUnPrunedIndex <= middleFileIndex);
            if (firstUnPrunedIndex < middleFileIndex
                    && files.get(firstUnPrunedIndex).getUpperBound() < middleFile.getUpperBound()) {
                assertTrue(CompareTo.isGreaterThanOrEqualTo(
                        firstUnPrunedFile.getTimestamp().plus(Duration.ofHours(1)), time.now()));
            }

            // Check the file right before the first un-pruned file.
            if (firstUnPrunedIndex > 0) {
                final PcesFile lastPrunedFile = files.get(firstUnPrunedIndex - 1);
                assertTrue(
                        lastPrunedFile.getTimestamp().isBefore(files.getLast().getTimestamp()));
            }

            // Check all remaining files to make sure we didn't accidentally delete something from the end
            final List<PcesFile> expectedFiles = new ArrayList<>();
            for (int index = firstUnPrunedIndex; index < NUM_FILES; index++) {
                expectedFiles.add(files.get(index));
            }
            assertIteratorEquality(expectedFiles.iterator(), freshFileTracker.getFileIterator(NO_LOWER_BOUND, 0));

            nextTimeIncrease = Duration.ofSeconds(random.nextInt(1, 20));
        }

        // tick time to 1 millisecond after the time of the middle file, so that it's now eligible for deletion
        time.tick(Duration.between(time.now(), endingTime).plus(Duration.ofMillis(1)));
        manager.pruneOldFiles(lastFile.getUpperBound() + 1);

        // Parse files afresh to make sure we aren't "cheating" by just
        // removing the in-memory descriptor without also removing the file on disk
        final PcesFileTracker freshFileTracker =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, false);

        final PcesFile firstUnPrunedFile = freshFileTracker.getFirstFile();

        int firstUnPrunedIndex = -1;
        for (int index = 0; index < files.size(); index++) {
            if (files.get(index).equals(firstUnPrunedFile)) {
                firstUnPrunedIndex = index;
                break;
            }
        }

        assertEquals(middleFileIndex + 1, firstUnPrunedIndex);
    }
}
