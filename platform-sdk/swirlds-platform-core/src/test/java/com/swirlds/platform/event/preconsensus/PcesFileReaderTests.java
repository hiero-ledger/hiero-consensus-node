// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertIteratorEquality;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.event.preconsensus.PcesFileManager.NO_LOWER_BOUND;
import static java.lang.Math.max;
import static org.hiero.consensus.model.event.AncientMode.BIRTH_ROUND_THRESHOLD;
import static org.hiero.consensus.model.event.AncientMode.GENERATION_THRESHOLD;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.streams.SerializableDataOutputStreamImpl;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.RecycleBinImpl;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.test.fixtures.TestRecycleBin;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.eventhandling.EventConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("PcesFileReader Tests")
class PcesFileReaderTests {

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    private Path fileDirectory = null;

    private Random random;

    private final int fileCount = 100;
    private Path recycleBinPath;

    @BeforeEach
    void beforeEach() throws IOException {
        FileUtils.deleteDirectory(testDirectory);
        fileDirectory = testDirectory.resolve("data").resolve("0");
        random = getRandomPrintSeed();
        recycleBinPath = testDirectory.resolve("recycle-bin");
    }

    @AfterEach
    void afterEach() throws IOException {
        FileUtils.deleteDirectory(testDirectory);
    }

    private PlatformContext buildContext(@NonNull final AncientMode ancientMode) {
        return buildContext(false, ancientMode);
    }

    private PlatformContext buildContext(final boolean permitGaps, @NonNull final AncientMode ancientMode) {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(PcesConfig_.DATABASE_DIRECTORY, testDirectory.resolve("data"))
                .withValue(PcesConfig_.PREFERRED_FILE_SIZE_MEGABYTES, 5)
                .withValue(PcesConfig_.PERMIT_GAPS, permitGaps)
                .withValue(PcesConfig_.COMPACT_LAST_FILE_ON_STARTUP, false)
                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, ancientMode == BIRTH_ROUND_THRESHOLD)
                .getOrCreateConfig();

        final Metrics metrics = new NoOpMetrics();
        return TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .withMetrics(metrics)
                .withRecycleBin(TestRecycleBin.getInstance())
                .withTestFileSystemManagerUnder(testDirectory)
                .build();
    }

    private PlatformContext buildContext(
            final boolean permitGaps, @NonNull final AncientMode ancientMode, @NonNull final Path recycleBinPath) {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(PcesConfig_.DATABASE_DIRECTORY, testDirectory.resolve("data"))
                .withValue(PcesConfig_.PREFERRED_FILE_SIZE_MEGABYTES, 5)
                .withValue(PcesConfig_.PERMIT_GAPS, permitGaps)
                .withValue(PcesConfig_.COMPACT_LAST_FILE_ON_STARTUP, false)
                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, ancientMode == BIRTH_ROUND_THRESHOLD)
                .getOrCreateConfig();

        final Metrics metrics = new NoOpMetrics();

        return TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .withMetrics(metrics)
                .withTime(Time.getCurrent())
                .withRecycleBin(new RecycleBinImpl(
                        metrics,
                        getStaticThreadManager(),
                        Time.getCurrent(),
                        recycleBinPath,
                        TestRecycleBin.MAXIMUM_FILE_AGE,
                        TestRecycleBin.MINIMUM_PERIOD))
                .withTestFileSystemManagerUnder(testDirectory)
                .build();
    }

    protected static Stream<Arguments> buildArguments() {
        return Stream.of(Arguments.of(GENERATION_THRESHOLD), Arguments.of(BIRTH_ROUND_THRESHOLD));
    }

    /**
     * Create a dummy file.
     *
     * @param descriptor a description of the file
     */
    public static void createDummyFile(final PcesFile descriptor) throws IOException {
        final Path parentDir = descriptor.getPath().getParent();
        if (!Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }
        final SerializableDataOutputStream out = new SerializableDataOutputStreamImpl(
                new FileOutputStream(descriptor.getPath().toFile()));
        out.writeInt(PcesFileVersion.currentVersionNumber());
        out.writeNormalisedString("foo bar baz");
        out.close();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Read Files In Order Test")
    void readFilesInOrderTest(@NonNull final AncientMode ancientMode) throws IOException {
        // Intentionally pick values close to wrapping around the 3 digit to 4 digit sequence number.
        // This will cause the files not to line up alphabetically, and this is a scenario that the
        // code should be able to handle.
        final long firstSequenceNumber = random.nextLong(950, 1000);

        final List<PcesFile> files = new ArrayList<>();

        final long maxDelta = random.nextLong(10, 20);
        long lowerBound = random.nextLong(0, 1000);
        final long nonExistentValue = lowerBound - 1;
        long upperBound = random.nextLong(lowerBound, lowerBound + maxDelta);
        Instant timestamp = Instant.now();

        for (long sequenceNumber = firstSequenceNumber;
                sequenceNumber < firstSequenceNumber + fileCount;
                sequenceNumber++) {

            final PcesFile file =
                    PcesFile.of(ancientMode, timestamp, sequenceNumber, lowerBound, upperBound, 0, fileDirectory);

            lowerBound = random.nextLong(lowerBound, upperBound + 1);
            upperBound = max(upperBound, random.nextLong(lowerBound, lowerBound + maxDelta));
            timestamp = timestamp.plusMillis(random.nextInt(1, 100_000));

            files.add(file);
            createDummyFile(file);
        }

        final PcesFileTracker fileTracker =
                PcesFileReader.readFilesFromDisk(buildContext(ancientMode), fileDirectory, 0, false, ancientMode);

        assertIteratorEquality(files.iterator(), fileTracker.getFileIterator(NO_LOWER_BOUND, 0));

        assertIteratorEquality(
                files.iterator(), fileTracker.getFileIterator(files.getFirst().getUpperBound(), 0));

        // attempt to start a non-existent ancient indicator
        assertIteratorEquality(files.iterator(), fileTracker.getFileIterator(nonExistentValue, 0));
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Read Files In Order Gap Test")
    void readFilesInOrderGapTest(@NonNull final AncientMode ancientMode) throws IOException {
        for (final boolean permitGaps : List.of(true, false)) {
            // Intentionally pick values close to wrapping around the 3 digit to 4 digit sequence number.
            // This will cause the files not to line up alphabetically, and this is a scenario that the
            // code should be able to handle.
            final long firstSequenceNumber = random.nextLong(950, 1000);

            final List<PcesFile> files = new ArrayList<>();

            final long maxDelta = random.nextLong(10, 20);
            long lowerBound = random.nextLong(0, 1000);
            long upperBound = random.nextLong(lowerBound, lowerBound + maxDelta);
            Instant timestamp = Instant.now();

            // Skip the file right in the middle of the sequence
            final long sequenceNumberToSkip = (2 * firstSequenceNumber + fileCount) / 2;

            for (long sequenceNumber = firstSequenceNumber;
                    sequenceNumber < firstSequenceNumber + fileCount;
                    sequenceNumber++) {

                final PcesFile file =
                        PcesFile.of(ancientMode, timestamp, sequenceNumber, lowerBound, upperBound, 0, fileDirectory);

                lowerBound = random.nextLong(lowerBound, upperBound + 1);
                upperBound = max(upperBound, random.nextLong(lowerBound, lowerBound + maxDelta));
                timestamp = timestamp.plusMillis(random.nextInt(1, 100_000));

                if (sequenceNumber == sequenceNumberToSkip) {
                    // Intentionally don't write a file
                    continue;
                }

                files.add(file);
                createDummyFile(file);
            }

            final PlatformContext platformContext = buildContext(permitGaps, ancientMode);

            if (permitGaps) {
                final PcesFileTracker fileTracker =
                        PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, true, ancientMode);
                // Gaps are allowed. We should see all files except for the one that was skipped.
                assertIteratorEquality(files.iterator(), fileTracker.getFileIterator(NO_LOWER_BOUND, 0));
            } else {
                // Gaps are not allowed.
                assertThrows(
                        IllegalStateException.class,
                        () -> PcesFileReader.readFilesFromDisk(
                                platformContext, fileDirectory, 0, permitGaps, ancientMode));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Read Files From Middle Test")
    void readFilesFromMiddleTest(@NonNull final AncientMode ancientMode) throws IOException {
        // Intentionally pick values close to wrapping around the 3 digit to 4 digit sequence number.
        // This will cause the files not to line up alphabetically, and this is a scenario that the
        // code should be able to handle.
        final long firstSequenceNumber = random.nextLong(950, 1000);

        final List<PcesFile> files = new ArrayList<>();

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
            upperBound = max(upperBound, random.nextLong(lowerBound, lowerBound + maxDelta));
            timestamp = timestamp.plusMillis(random.nextInt(1, 100_000));

            files.add(file);
            createDummyFile(file);
        }

        final PcesFileTracker fileTracker =
                PcesFileReader.readFilesFromDisk(buildContext(ancientMode), fileDirectory, 0, false, ancientMode);

        // For this test, we want to iterate over files so that we are guaranteed to observe every event
        // with an ancient indicator greater than or equal to the target threshold. Choose an ancient indicator
        // that falls roughly in the middle of the sequence of files.
        final long targetAncientIdentifier =
                (files.getFirst().getUpperBound() + files.get(fileCount - 1).getUpperBound()) / 2;

        final List<PcesFile> iteratedFiles = new ArrayList<>();
        fileTracker.getFileIterator(targetAncientIdentifier, 0).forEachRemaining(iteratedFiles::add);

        // Find the index in the file list that was returned first by the iterator
        int indexOfFirstFile = 0;
        for (; indexOfFirstFile < fileCount; indexOfFirstFile++) {
            if (files.get(indexOfFirstFile).equals(iteratedFiles.getFirst())) {
                break;
            }
        }

        // The file immediately before the returned file should not contain any targeted events
        assertTrue(files.get(indexOfFirstFile - 1).getUpperBound() < targetAncientIdentifier);

        // The first file returned from the iterator should
        // have an upper bound greater than or equal to the target ancient indicator.
        assertTrue(iteratedFiles.getFirst().getUpperBound() >= targetAncientIdentifier);

        // Make sure that the iterator returns files in the correct order.
        final List<PcesFile> expectedFiles = new ArrayList<>(iteratedFiles.size());
        for (int index = indexOfFirstFile; index < fileCount; index++) {
            expectedFiles.add(files.get(index));
        }
        assertIteratorEquality(expectedFiles.iterator(), iteratedFiles.iterator());
    }

    /**
     * Similar to the other test that starts iteration in the middle, except that files will have the same bounds with
     * high probability. Not a scenario we are likely to encounter in production, but it's a tricky edge case we need to
     * handle elegantly.
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Read Files From Middle Repeating Boundaries Test")
    void readFilesFromMiddleRepeatingBoundariesTest(@NonNull final AncientMode ancientMode) throws IOException {
        // Intentionally pick values close to wrapping around the 3 digit to 4 digit sequence number.
        // This will cause the files not to line up alphabetically, and this is a scenario that the
        // code should be able to handle.
        final long firstSequenceNumber = random.nextLong(950, 1000);

        final List<PcesFile> files = new ArrayList<>();

        final long maxDelta = random.nextLong(10, 20);
        long lowerBound = random.nextLong(0, 1000);
        long upperBound = random.nextLong(lowerBound, lowerBound + maxDelta);
        Instant timestamp = Instant.now();

        for (long sequenceNumber = firstSequenceNumber;
                sequenceNumber < firstSequenceNumber + fileCount;
                sequenceNumber++) {

            final PcesFile file =
                    PcesFile.of(ancientMode, timestamp, sequenceNumber, lowerBound, upperBound, 0, fileDirectory);

            // Advance the bounds only 10% of the time
            if (random.nextLong() < 0.1) {
                lowerBound = random.nextLong(lowerBound, upperBound + 1);
                upperBound = max(upperBound, random.nextLong(lowerBound, lowerBound + maxDelta));
            }
            timestamp = timestamp.plusMillis(random.nextInt(1, 100_000));

            files.add(file);
            createDummyFile(file);
        }

        final PcesFileTracker fileTracker =
                PcesFileReader.readFilesFromDisk(buildContext(ancientMode), fileDirectory, 0, false, ancientMode);

        // For this test, we want to iterate over files so that we are guaranteed to observe every event
        // with an ancient indicator greater than or equal to the target. Choose an ancient indicator that falls
        // roughly in the middle of the sequence of files.
        final long targetAncientIdentifier =
                (files.getFirst().getUpperBound() + files.get(fileCount - 1).getUpperBound()) / 2;

        final List<PcesFile> iteratedFiles = new ArrayList<>();
        fileTracker.getFileIterator(targetAncientIdentifier, 0).forEachRemaining(iteratedFiles::add);

        // Find the index in the file list that was returned first by the iterator
        int indexOfFirstFile = 0;
        for (; indexOfFirstFile < fileCount; indexOfFirstFile++) {
            if (files.get(indexOfFirstFile).equals(iteratedFiles.getFirst())) {
                break;
            }
        }

        // The file immediately before the returned file should not contain any targeted events
        assertTrue(files.get(indexOfFirstFile - 1).getUpperBound() < targetAncientIdentifier);

        // The first file returned from the iterator should
        // have an upper bound greater than or equal to the target ancient indicator.
        assertTrue(iteratedFiles.getFirst().getUpperBound() >= targetAncientIdentifier);

        // Make sure that the iterator returns files in the correct order.
        final List<PcesFile> expectedFiles = new ArrayList<>(iteratedFiles.size());
        for (int index = indexOfFirstFile; index < fileCount; index++) {
            expectedFiles.add(files.get(index));
        }
        assertIteratorEquality(expectedFiles.iterator(), iteratedFiles.iterator());
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Read Files From High ancient indicator Test")
    void readFilesFromHighAncientIdentifierTest(@NonNull final AncientMode ancientMode) throws IOException {
        // Intentionally pick values close to wrapping around the 3 digit to 4 digit sequence number.
        // This will cause the files not to line up alphabetically, and this is a scenario that the
        // code should be able to handle.
        final long firstSequenceNumber = random.nextLong(950, 1000);

        final List<PcesFile> files = new ArrayList<>();

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
            upperBound = max(upperBound, random.nextLong(lowerBound, lowerBound + maxDelta));
            timestamp = timestamp.plusMillis(random.nextInt(1, 100_000));

            files.add(file);
            createDummyFile(file);
        }

        final PcesFileTracker fileTracker =
                PcesFileReader.readFilesFromDisk(buildContext(ancientMode), fileDirectory, 0, false, ancientMode);

        // Request an ancient indicator higher than all files in the data store
        final long targetAncientIdentifier = files.get(fileCount - 1).getUpperBound() + 1;

        final Iterator<PcesFile> iterator = fileTracker.getFileIterator(targetAncientIdentifier, 0);
        assertFalse(iterator.hasNext());
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Read Files From Empty Stream Test")
    void readFilesFromEmptyStreamTest(@NonNull final AncientMode ancientMode) {
        assertThrows(
                NoSuchFileException.class,
                () -> PcesFileReader.readFilesFromDisk(
                        buildContext(ancientMode), fileDirectory, 0, false, ancientMode));
    }

    /**
     * When fixing a discontinuity, invalid files are moved to a "recycle bin" directory. This method validates that
     * behavior.
     */
    private void validateRecycledFiles(
            @NonNull final List<PcesFile> filesThatShouldBePresent, @NonNull final List<PcesFile> allFiles)
            throws IOException {

        final Set<Path> recycledFiles = new HashSet<>();
        try (final Stream<Path> stream = Files.walk(this.recycleBinPath)) {
            stream.forEach(file -> recycledFiles.add(file.getFileName()));
        }

        final Set<PcesFile> filesThatShouldBePresentSet = new HashSet<>(filesThatShouldBePresent);

        for (final PcesFile file : allFiles) {
            if (filesThatShouldBePresentSet.contains(file)) {
                assertTrue(Files.exists(file.getPath()));
            } else {
                assertTrue(recycledFiles.contains(file.getPath().getFileName()), file.toString());
            }
        }
    }

    /**
     *  Given that allowing gaps or discontinuities in the origin block of the PcesFile is likely to either lead to ISSes or, more likely, cause
     *  events to be added to the hashgraph without their parents being added,
     * the aim of the test is asserting that readFilesFromDisk is able to detect gaps or discontinuities exist in the existing PcesFiles.
     * </br>
     * This test, generates a list of files PcesFiles and places a discontinuity in the origin block randomly in the list.
     * The sequence numbers are intentionally picked close to wrapping around the 3 digit to 4 digit, to cause the files not to line up
     * alphabetically, and test the code support for that.
     * The scenarios under test are:
     *  * readFilesFromDisk is asked to read at the discontinuity origin block
     *  * readFilesFromDisk is asked to read after the discontinuity origin block
     *  * readFilesFromDisk is asked to read before the discontinuity origin block
     *  * readFilesFromDisk is asked to read a non-existent origin block
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Start And First File Discontinuity In Middle Test")
    void startAtFirstFileDiscontinuityInMiddleTest(@NonNull final AncientMode ancientMode) throws IOException {
        final List<PcesFile> filesBeforeDiscontinuity = new ArrayList<>();
        final List<PcesFile> filesAfterDiscontinuity = new ArrayList<>();

        final List<PcesFile> files = new ArrayList<>();

        // Intentionally pick values close to wrapping around the 3 digit to 4 digit sequence number.
        // This will cause the files not to line up alphabetically, and this is a scenario that the
        // code should be able to handle.
        final long firstSequenceNumber = random.nextLong(950, 1000);

        final long maxDelta = random.nextLong(10, 20);
        long lowerBound = random.nextLong(0, 1000);
        long upperBound = random.nextLong(lowerBound, lowerBound + maxDelta);
        Instant timestamp = Instant.now();
        final long startingOrigin = random.nextLong(1, 1000);
        final long discontinuity = startingOrigin + random.nextLong(1, 1000);
        final int n = random.nextInt(2, fileCount); // The index of the fileCount where the
        // discontinuity will be placed

        for (int index = 0; index < fileCount; index++) {
            final long sequenceNumber = firstSequenceNumber + index;
            final var isPreDiscontinuity = index < n;
            final var org = isPreDiscontinuity ? startingOrigin : discontinuity;
            final var file =
                    PcesFile.of(ancientMode, timestamp, sequenceNumber, lowerBound, upperBound, org, fileDirectory);
            createDummyFile(file);
            if (isPreDiscontinuity) {
                filesBeforeDiscontinuity.add(file);
            } else {
                filesAfterDiscontinuity.add(file);
            }
            lowerBound = random.nextLong(lowerBound, upperBound + 1);
            upperBound = max(upperBound, random.nextLong(lowerBound, lowerBound + maxDelta));
            timestamp = timestamp.plusMillis(random.nextInt(1, 100_000));
        }

        final PlatformContext platformContext = buildContext(false, ancientMode, recycleBinPath);
        // Scenario 1: choose an origin that lands on the discontinuity exactly.
        final PcesFileTracker fileTracker1 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, discontinuity, false, ancientMode);
        assertIteratorEquality(
                filesAfterDiscontinuity.iterator(), fileTracker1.getFileIterator(NO_LOWER_BOUND, discontinuity));

        // Scenario 2: choose an origin that lands after the discontinuity.
        final long startingRound2 = random.nextLong(discontinuity + 1, discontinuity + 1000);
        final PcesFileTracker fileTracker2 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound2, false, ancientMode);
        assertIteratorEquality(
                filesAfterDiscontinuity.iterator(), fileTracker2.getFileIterator(NO_LOWER_BOUND, startingRound2));

        // Scenario 3: choose an origin that comes before the discontinuity. This will cause the files
        // after the discontinuity to be deleted.
        final long startingRound3 = random.nextLong(startingOrigin, discontinuity);
        final PcesFileTracker fileTracker3 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound3, false, ancientMode);

        assertIteratorEquality(
                filesBeforeDiscontinuity.iterator(), fileTracker3.getFileIterator(NO_LOWER_BOUND, startingRound3));

        validateRecycledFiles(filesBeforeDiscontinuity, files);

        // Scenario 4: choose an origin that is incompatible with all state files. This will cause all remaining
        // files to be deleted.
        final long startingRound4 = 0;
        final PcesFileTracker fileTracker4 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound4, false, ancientMode);

        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker4.getFileIterator(NO_LOWER_BOUND, startingRound4));

        validateRecycledFiles(List.of(), files);
    }

    /**
     * In this test, a discontinuity is placed in the middle of the stream. We begin iterating at a file that comes
     * before the discontinuity, but it isn't the first file in the stream.
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Start At Middle File Discontinuity In Middle Test")
    void startAtMiddleFileDiscontinuityInMiddleTest(@NonNull final AncientMode ancientMode) throws IOException {
        final List<PcesFile> filesBeforeDiscontinuity = new ArrayList<>();
        final List<PcesFile> filesAfterDiscontinuity = new ArrayList<>();

        final List<PcesFile> files = new ArrayList<>();

        // Intentionally pick values close to wrapping around the 3 digit to 4 digit sequence number.
        // This will cause the files not to line up alphabetically, and this is a scenario that the
        // code should be able to handle.
        final int firstSequenceNumber = random.nextInt(950, 1000);

        final long maxDelta = random.nextLong(10, 20);
        long lowerBound = random.nextLong(0, 1000);
        long upperBound = random.nextLong(lowerBound + 1, lowerBound + maxDelta);
        Instant timestamp = Instant.now();

        final int discontinuitySequenceNumber =
                (int) random.nextLong(firstSequenceNumber + 2, firstSequenceNumber + fileCount - 1);

        final int startSequenceNumber = random.nextInt(firstSequenceNumber, discontinuitySequenceNumber - 1);

        final long startingOrigin = random.nextLong(1, 1000);
        long origin = startingOrigin;

        for (long sequenceNumber = firstSequenceNumber;
                sequenceNumber < firstSequenceNumber + fileCount;
                sequenceNumber++) {

            if (sequenceNumber == discontinuitySequenceNumber) {
                origin = random.nextLong(origin + 1, origin + 1000);
            }

            final PcesFile file =
                    PcesFile.of(ancientMode, timestamp, sequenceNumber, lowerBound, upperBound, origin, fileDirectory);

            lowerBound = random.nextLong(lowerBound + 1, upperBound + 1);
            upperBound = random.nextLong(upperBound + 1, upperBound + maxDelta);
            timestamp = timestamp.plusMillis(random.nextInt(1, 100_000));

            if (sequenceNumber >= startSequenceNumber) {
                files.add(file);
                if (sequenceNumber < discontinuitySequenceNumber) {
                    filesBeforeDiscontinuity.add(file);
                } else {
                    filesAfterDiscontinuity.add(file);
                }
            }
            createDummyFile(file);
        }

        // Note that the file at index 0 is not the first file in the stream,
        // but it is the first file we want to iterate
        final long startAncientIdentifier = files.getFirst().getUpperBound();

        final PlatformContext platformContext = buildContext(false, ancientMode, testDirectory.resolve("recycle-bin"));

        // Scenario 1: choose an origin that lands on the discontinuity exactly.
        final long startingRound1 = origin;
        final PcesFileTracker fileTracker1 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound1, false, ancientMode);
        assertIteratorEquality(
                filesAfterDiscontinuity.iterator(),
                fileTracker1.getFileIterator(startAncientIdentifier, startingRound1));

        // Scenario 2: choose an origin that lands after the discontinuity.
        final long startingRound2 = random.nextLong(origin + 1, origin + 1000);
        final PcesFileTracker fileTracker2 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound2, false, ancientMode);
        assertIteratorEquality(
                filesAfterDiscontinuity.iterator(),
                fileTracker2.getFileIterator(startAncientIdentifier, startingRound2));

        // Scenario 3: choose an origin that comes before the discontinuity. This will cause the files
        // after the discontinuity to be deleted.
        final long startingRound3 = random.nextLong(startingOrigin, max(origin - 1, startingOrigin + 1));
        final PcesFileTracker fileTracker3 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound3, false, ancientMode);
        assertIteratorEquality(
                filesBeforeDiscontinuity.iterator(),
                fileTracker3.getFileIterator(startAncientIdentifier, startingRound3));

        validateRecycledFiles(filesBeforeDiscontinuity, files);

        // Scenario 4: choose an origin that is incompatible with all state files. This will cause all remaining
        // files to be deleted.
        final long startingRound4 = 0;
        final PcesFileTracker fileTracker4 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound4, false, ancientMode);
        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker4.getFileIterator(startAncientIdentifier, startingRound4));

        validateRecycledFiles(List.of(), files);
    }

    /**
     * In this test, a discontinuity is placed in the middle of the stream, and we begin iterating on that exact file.
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Start At Middle File Discontinuity In Middle Test")
    void startAtDiscontinuityInMiddleTest(@NonNull final AncientMode ancientMode) throws IOException {
        final List<PcesFile> filesBeforeDiscontinuity = new ArrayList<>();
        final List<PcesFile> filesAfterDiscontinuity = new ArrayList<>();

        final List<PcesFile> files = new ArrayList<>();

        // Intentionally pick values close to wrapping around the 3 digit to 4 digit sequence number.
        // This will cause the files not to line up alphabetically, and this is a scenario that the
        // code should be able to handle.
        final int firstSequenceNumber = random.nextInt(950, 1000);

        // In this test, sequence numbers are intentionally chosen so that the min/max sequence number always
        // increases by at least 1 from file to file. The purpose for this is to make validation logic simpler.

        final long maxDelta = random.nextLong(10, 20);
        long lowerBound = random.nextLong(0, 1000);
        long upperBound = random.nextLong(lowerBound + 1, lowerBound + maxDelta);
        Instant timestamp = Instant.now();

        final int discontinuitySequenceNumber =
                (int) random.nextLong(firstSequenceNumber + 2, firstSequenceNumber + fileCount - 1);

        final long startingOrigin = random.nextLong(1, 1000);
        long origin = startingOrigin;

        for (long sequenceNumber = firstSequenceNumber;
                sequenceNumber < firstSequenceNumber + fileCount;
                sequenceNumber++) {

            if (sequenceNumber == discontinuitySequenceNumber) {
                origin = random.nextLong(origin + 1, origin + 1000);
            }

            final PcesFile file =
                    PcesFile.of(ancientMode, timestamp, sequenceNumber, lowerBound, upperBound, origin, fileDirectory);

            lowerBound = random.nextLong(lowerBound + 1, upperBound + 1);
            upperBound = random.nextLong(upperBound + 1, upperBound + maxDelta);
            timestamp = timestamp.plusMillis(random.nextInt(1, 100_000));

            files.add(file);
            if (sequenceNumber < discontinuitySequenceNumber) {
                filesBeforeDiscontinuity.add(file);
            } else {
                filesAfterDiscontinuity.add(file);
            }
            createDummyFile(file);
        }

        // Note that the file at index 0 is not the first file in the stream,
        // but it is the first file we want to iterate
        final long startAncientIdentifier = filesAfterDiscontinuity.getFirst().getUpperBound();

        final PlatformContext platformContext = buildContext(false, ancientMode, testDirectory.resolve("recycle-bin"));
        // Scenario 1: choose an origin that lands on the discontinuity exactly.
        final long startingRound1 = origin;
        final PcesFileTracker fileTracker1 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound1, false, ancientMode);
        assertIteratorEquality(
                filesAfterDiscontinuity.iterator(),
                fileTracker1.getFileIterator(startAncientIdentifier, startingRound1));

        // Scenario 2: choose an origin that lands after the discontinuity.
        final long startingRound2 = random.nextLong(origin + 1, origin + 1000);
        final PcesFileTracker fileTracker2 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound2, false, ancientMode);
        assertIteratorEquality(
                filesAfterDiscontinuity.iterator(),
                fileTracker2.getFileIterator(startAncientIdentifier, startingRound2));

        // Scenario 3: choose an origin that comes before the discontinuity. This will cause the files
        // after the discontinuity to be deleted.
        final long startingRound3 = random.nextLong(startingOrigin, max(origin - 1, startingOrigin + 1));
        final PcesFileTracker fileTracker3 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound3, false, ancientMode);
        // There is no files with a compatible origin and events with ancient indicators in the span we want.
        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker3.getFileIterator(startAncientIdentifier, startingRound3));

        validateRecycledFiles(filesBeforeDiscontinuity, files);

        // Scenario 4: choose an origin that is incompatible with all state files. This will cause all remaining
        // files to be deleted.
        final long startingRound4 = 0;
        final PcesFileTracker fileTracker4 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound4, false, ancientMode);
        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker4.getFileIterator(startAncientIdentifier, startingRound4));

        validateRecycledFiles(List.of(), files);
    }

    /**
     * In this test, a discontinuity is placed in the middle of the stream, and we begin iterating after that file.
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Start After Discontinuity In Middle Test")
    void startAfterDiscontinuityInMiddleTest(@NonNull final AncientMode ancientMode) throws IOException {
        final List<PcesFile> filesBeforeDiscontinuity = new ArrayList<>();
        final List<PcesFile> filesAfterDiscontinuity = new ArrayList<>();

        final List<PcesFile> files = new ArrayList<>();

        // Intentionally pick values close to wrapping around the 3 digit to 4 digit sequence number.
        // This will cause the files not to line up alphabetically, and this is a scenario that the
        // code should be able to handle.
        final int firstSequenceNumber = random.nextInt(950, 1000);

        // In this test, sequence numbers are intentionally chosen so that the min/max sequence number always
        // increases by at least 1 from file to file. The purpose for this is to make validation logic simpler.

        final long maxDelta = random.nextLong(10, 20);
        long lowerBound = random.nextLong(0, 1000);
        long upperBound = random.nextLong(lowerBound + 1, lowerBound + maxDelta);
        Instant timestamp = Instant.now();

        final int discontinuitySequenceNumber =
                (int) random.nextLong(firstSequenceNumber + 2, firstSequenceNumber + fileCount - 1);

        final long startingOrigin = random.nextLong(1, 1000);
        long origin = startingOrigin;

        for (long sequenceNumber = firstSequenceNumber;
                sequenceNumber < firstSequenceNumber + fileCount;
                sequenceNumber++) {

            if (sequenceNumber == discontinuitySequenceNumber) {
                origin = random.nextLong(origin + 1, origin + 1000);
            }

            final PcesFile file =
                    PcesFile.of(ancientMode, timestamp, sequenceNumber, lowerBound, upperBound, origin, fileDirectory);

            lowerBound = random.nextLong(lowerBound + 1, upperBound + 1);
            upperBound = random.nextLong(upperBound + 1, upperBound + maxDelta);
            timestamp = timestamp.plusMillis(random.nextInt(1, 100_000));

            files.add(file);
            if (sequenceNumber >= discontinuitySequenceNumber) {
                filesAfterDiscontinuity.add(file);
            } else {
                filesBeforeDiscontinuity.add(file);
            }
            createDummyFile(file);
        }

        // Note that the file at index 0 is not the first file in the stream,
        // but it is the first file we want to iterate
        final long startAncientBoundary = filesAfterDiscontinuity.getFirst().getUpperBound();

        final PlatformContext platformContext = buildContext(false, ancientMode, testDirectory.resolve("recycle-bin"));

        // Scenario 1: choose an origin that lands on the discontinuity exactly.
        final long startingRound1 = origin;
        final PcesFileTracker fileTracker1 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound1, false, ancientMode);
        assertIteratorEquality(
                filesAfterDiscontinuity.iterator(), fileTracker1.getFileIterator(startAncientBoundary, startingRound1));

        // Scenario 2: choose an origin that lands after the discontinuity.
        final long startingRound2 = random.nextLong(origin + 1, origin + 1000);
        final PcesFileTracker fileTracker2 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound2, false, ancientMode);
        assertIteratorEquality(
                filesAfterDiscontinuity.iterator(), fileTracker2.getFileIterator(startAncientBoundary, startingRound2));

        // Scenario 3: choose an origin that comes before the discontinuity. This will cause the files
        // after the discontinuity to be deleted.
        final long startingRound3 = random.nextLong(startingOrigin, origin - 1);
        final PcesFileTracker fileTracker3 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound3, false, ancientMode);
        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker3.getFileIterator(startAncientBoundary, startingRound3));

        validateRecycledFiles(filesBeforeDiscontinuity, files);

        // Scenario 4: choose an origin that is incompatible with all state files. This will cause all remaining
        // files to be deleted.
        final long startingRound4 = 0;
        final PcesFileTracker fileTracker4 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound4, false, ancientMode);
        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker4.getFileIterator(startAncientBoundary, startingRound4));

        validateRecycledFiles(List.of(), files);
    }

    @Test
    void readFilesOfBothTypesTest() throws IOException {
        // Intentionally pick values close to wrapping around the 3 digit to 4 digit sequence number.
        // This will cause the files not to line up alphabetically, and this is a scenario that the
        // code should be able to handle.
        final long firstSequenceNumber = random.nextLong(950, 1000);

        final long maxDelta = random.nextLong(10, 20);
        long lowerBound = random.nextLong(0, 1000);
        long upperBound = random.nextLong(lowerBound, lowerBound + maxDelta);
        Instant timestamp = Instant.now();

        // Phase 1: write files using generations

        final List<PcesFile> generationFiles = new ArrayList<>();

        for (long sequenceNumber = firstSequenceNumber;
                sequenceNumber < firstSequenceNumber + fileCount;
                sequenceNumber++) {

            final PcesFile file = PcesFile.of(
                    GENERATION_THRESHOLD, timestamp, sequenceNumber, lowerBound, upperBound, 0, fileDirectory);

            lowerBound = random.nextLong(lowerBound, upperBound + 1);
            upperBound = max(upperBound, random.nextLong(lowerBound, lowerBound + maxDelta));
            timestamp = timestamp.plusMillis(random.nextInt(1, 100_000));

            generationFiles.add(file);
            createDummyFile(file);
        }

        // Phase 2: write files using birth rounds
        final List<PcesFile> birthRoundFiles = new ArrayList<>();

        for (long sequenceNumber = firstSequenceNumber;
                sequenceNumber < firstSequenceNumber + fileCount;
                sequenceNumber++) {

            final PcesFile file = PcesFile.of(
                    BIRTH_ROUND_THRESHOLD, timestamp, sequenceNumber, lowerBound, upperBound, 0, fileDirectory);

            lowerBound = random.nextLong(lowerBound, upperBound + 1);
            upperBound = max(upperBound, random.nextLong(lowerBound, lowerBound + maxDelta));
            timestamp = timestamp.plusMillis(random.nextInt(1, 100_000));

            birthRoundFiles.add(file);
            createDummyFile(file);
        }

        // Phase 3: read files of both types

        final PcesFileTracker generationFileTracker = PcesFileReader.readFilesFromDisk(
                buildContext(GENERATION_THRESHOLD), fileDirectory, 0, false, GENERATION_THRESHOLD);

        final PcesFileTracker birthRoundFileTracker = PcesFileReader.readFilesFromDisk(
                buildContext(BIRTH_ROUND_THRESHOLD), fileDirectory, 0, false, BIRTH_ROUND_THRESHOLD);

        assertIteratorEquality(generationFiles.iterator(), generationFileTracker.getFileIterator(NO_LOWER_BOUND, 0));
        assertIteratorEquality(birthRoundFiles.iterator(), birthRoundFileTracker.getFileIterator(NO_LOWER_BOUND, 0));
    }
}
