// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertIteratorEquality;
import static com.swirlds.platform.event.preconsensus.PcesFileManager.NO_LOWER_BOUND;
import static com.swirlds.platform.event.preconsensus.PcesFileReaderTests.createDummyFile;
import static org.hiero.consensus.model.event.AncientMode.BIRTH_ROUND_THRESHOLD;
import static org.hiero.consensus.model.event.AncientMode.GENERATION_THRESHOLD;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.config.FileSystemManagerConfig_;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.common.utility.CompareTo;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.eventhandling.EventConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link PcesFileManager}
 */
@DisplayName("PcesFileManager Tests")
class PcesFileManagerTests {
    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    private Random random;

    private final int fileCount = 100;

    private List<PcesFile> files;

    private Path fileDirectory = null;

    @BeforeEach
    void beforeEach() throws IOException {
        FileUtils.deleteDirectory(testDirectory);
        fileDirectory = testDirectory.resolve("data").resolve("0");
        random = getRandomPrintSeed();
        files = new ArrayList<>();
    }

    protected static Stream<Arguments> buildArguments() {
        return Stream.of(Arguments.of(GENERATION_THRESHOLD), Arguments.of(BIRTH_ROUND_THRESHOLD));
    }

    private PlatformContext buildContext(@NonNull final AncientMode ancientMode, @NonNull final Time time) {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(PcesConfig_.DATABASE_DIRECTORY, testDirectory.resolve("data"))
                .withValue(FileSystemManagerConfig_.ROOT_PATH, testDirectory.resolve("data"))
                .withValue(PcesConfig_.PREFERRED_FILE_SIZE_MEGABYTES, 5)
                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, ancientMode == BIRTH_ROUND_THRESHOLD)
                .withValue(PcesConfig_.PERMIT_GAPS, false)
                .withValue(PcesConfig_.COMPACT_LAST_FILE_ON_STARTUP, false)
                .getOrCreateConfig();

        return TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .withTime(time)
                .build();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Generate Descriptors With Manager Test")
    void generateDescriptorsWithManagerTest(@NonNull final AncientMode ancientMode) throws IOException {
        final PlatformContext platformContext = buildContext(ancientMode, Time.getCurrent());

        final long maxDelta = random.nextLong(10, 20);
        long lowerBound = random.nextLong(0, 1000);
        long upperBound = random.nextLong(lowerBound, lowerBound + maxDelta);
        Instant timestamp = Instant.now();

        final PcesFileManager generatingManager =
                new PcesFileManager(platformContext, new PcesFileTracker(ancientMode), NodeId.of(0), 0);

        for (int i = 0; i < fileCount; i++) {
            final PcesFile file = generatingManager.getNextFileDescriptor(lowerBound, upperBound);

            assertTrue(file.getLowerBound() >= lowerBound);
            assertTrue(file.getUpperBound() >= upperBound);

            // Intentionally allow bounds to be chosen that may not be legal (i.e. ancient threshold decreases)
            lowerBound = random.nextLong(lowerBound - 1, upperBound + 1);
            upperBound = random.nextLong(lowerBound, lowerBound + maxDelta);
            timestamp = timestamp.plusMillis(random.nextInt(1, 100_000));

            files.add(file);
            createDummyFile(file);
        }

        final PcesFileTracker fileTracker =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, false, ancientMode);

        assertIteratorEquality(files.iterator(), fileTracker.getFileIterator(NO_LOWER_BOUND, 0));
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Incremental Pruning By Ancient Boundary Test")
    void incrementalPruningByAncientBoundaryTest(@NonNull final AncientMode ancientMode) throws IOException {
        // Intentionally pick values close to wrapping around the 3 digit to 4 digit sequence number.
        // This will cause the files not to line up alphabetically, and this is a scenario that the
        // code should be able to handle.
        final long firstSequenceNumber = random.nextLong(950, 1000);

        final long maxDelta = random.nextLong(10, 20);
        long lowerBound = random.nextLong(0, 1000);
        long upperBound = random.nextLong(lowerBound, lowerBound + maxDelta);
        Instant timestamp = Instant.now();

        for (long sequenceNumber = firstSequenceNumber;
                sequenceNumber < firstSequenceNumber + fileCount;
                sequenceNumber++) {

            final PcesFile file =
                    PcesFile.of(ancientMode, timestamp, sequenceNumber, lowerBound, upperBound, 0, fileDirectory);

            lowerBound = random.nextLong(lowerBound, upperBound + 1);
            upperBound = Math.max(upperBound + 1, random.nextLong(lowerBound, lowerBound + maxDelta));
            timestamp = timestamp.plusMillis(random.nextInt(1, 100_000));

            files.add(file);
            createDummyFile(file);
        }

        // Choose a file in the middle. The goal is to incrementally purge all files before this file, but not
        // to purge this file or any of the ones after it.
        final int middleFileIndex = fileCount / 2;

        final PcesFile firstFile = files.getFirst();
        final PcesFile middleFile = files.get(middleFileIndex);
        final PcesFile lastFile = files.getLast();

        // Set the far in the future, we want all files to be GC eligible by temporal reckoning.
        final FakeTime time = new FakeTime(lastFile.getTimestamp().plus(Duration.ofHours(1)), Duration.ZERO);
        final PlatformContext platformContext = buildContext(ancientMode, time);

        final PcesFileTracker fileTracker =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, false, ancientMode);
        final PcesFileManager manager = new PcesFileManager(platformContext, fileTracker, NodeId.of(0), 0);

        assertIteratorEquality(files.iterator(), fileTracker.getFileIterator(NO_LOWER_BOUND, 0));

        // Increase the pruned ancient threshold a little at a time,
        // until the middle file is almost GC eligible but not quite.
        for (long ancientThreshold = firstFile.getUpperBound() - 100;
                ancientThreshold <= middleFile.getUpperBound();
                ancientThreshold++) {

            manager.pruneOldFiles(ancientThreshold);

            // Parse files afresh to make sure we aren't "cheating" by just
            // removing the in-memory descriptor without also removing the file on disk
            final PcesFileTracker freshFileTracker =
                    PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, false, ancientMode);

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
            if (firstUnPrunedIndex < middleFileIndex) {
                assertTrue(firstUnPrunedFile.getUpperBound() >= ancientThreshold);
            }

            // Check the file right before the first un-pruned file.
            if (firstUnPrunedIndex > 0) {
                final PcesFile lastPrunedFile = files.get(firstUnPrunedIndex - 1);
                assertTrue(lastPrunedFile.getUpperBound() < ancientThreshold);
            }

            // Check all remaining files to make sure we didn't accidentally delete something from the end
            final List<PcesFile> expectedFiles = new ArrayList<>();
            for (int index = firstUnPrunedIndex; index < fileCount; index++) {
                expectedFiles.add(files.get(index));
            }

            // iterate over all files in the fresh file tracker to make sure they match expected
            assertIteratorEquality(expectedFiles.iterator(), freshFileTracker.getFileIterator(NO_LOWER_BOUND, 0));
        }

        // Now, prune files so that the middle file is no longer needed.
        manager.pruneOldFiles(middleFile.getUpperBound() + 1);

        // Parse files afresh to make sure we aren't "cheating" by just
        // removing the in-memory descriptor without also removing the file on disk
        final PcesFileTracker freshFileTracker =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, false, ancientMode);

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

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Incremental Pruning By Timestamp Test")
    void incrementalPruningByTimestampTest(@NonNull final AncientMode ancientMode) throws IOException {
        // Intentionally pick values close to wrapping around the 3 digit to 4 digit sequence number.
        // This will cause the files not to line up alphabetically, and this is a scenario that the
        // code should be able to handle.
        final long firstSequenceNumber = random.nextLong(950, 1000);

        final long maxDelta = random.nextLong(10, 20);
        long lowerBound = random.nextLong(0, 1000);
        long upperBound = random.nextLong(lowerBound, lowerBound + maxDelta);
        Instant timestamp = Instant.now();

        for (long sequenceNumber = firstSequenceNumber;
                sequenceNumber < firstSequenceNumber + fileCount;
                sequenceNumber++) {

            final PcesFile file =
                    PcesFile.of(ancientMode, timestamp, sequenceNumber, lowerBound, upperBound, 0, fileDirectory);

            lowerBound = random.nextLong(lowerBound, upperBound + 1);
            upperBound = Math.max(upperBound, random.nextLong(lowerBound, lowerBound + maxDelta));
            timestamp = timestamp.plusMillis(random.nextInt(1, 100_000));

            files.add(file);
            createDummyFile(file);
        }

        // Choose a file in the middle. The goal is to incrementally purge all files before this file, but not
        // to purge this file or any of the ones after it.
        final int middleFileIndex = fileCount / 2;

        final PcesFile firstFile = files.getFirst();
        final PcesFile middleFile = files.get(middleFileIndex);
        final PcesFile lastFile = files.getLast();

        // Set the clock before the first file is not garbage collection eligible
        final FakeTime time = new FakeTime(firstFile.getTimestamp().plus(Duration.ofMinutes(59)), Duration.ZERO);
        final PlatformContext platformContext = buildContext(ancientMode, time);

        final PcesFileTracker fileTracker =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, false, ancientMode);
        final PcesFileManager manager = new PcesFileManager(platformContext, fileTracker, NodeId.of(0), 0);

        assertIteratorEquality(files.iterator(), fileTracker.getFileIterator(NO_LOWER_BOUND, 0));

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
                    PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, false, ancientMode);

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
                assertTrue(lastPrunedFile.getTimestamp().isBefore(timestamp));
            }

            // Check all remaining files to make sure we didn't accidentally delete something from the end
            final List<PcesFile> expectedFiles = new ArrayList<>();
            for (int index = firstUnPrunedIndex; index < fileCount; index++) {
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
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, false, ancientMode);

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
