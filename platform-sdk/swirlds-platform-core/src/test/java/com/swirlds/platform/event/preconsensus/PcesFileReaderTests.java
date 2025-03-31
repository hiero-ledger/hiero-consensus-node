// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertIteratorEquality;
import static com.swirlds.platform.event.preconsensus.PcesFileManager.NO_LOWER_BOUND;
import static org.hiero.consensus.model.event.AncientMode.BIRTH_ROUND_THRESHOLD;
import static org.hiero.consensus.model.event.AncientMode.GENERATION_THRESHOLD;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.test.fixtures.platform.TestPlatformContexts;
import com.swirlds.platform.test.fixtures.event.preconsensus.PcesTestFilesGenerator;
import com.swirlds.platform.test.fixtures.event.preconsensus.PcesTestFilesGenerator.Range;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.hiero.consensus.model.event.AncientMode;
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
     * Default range for the origin value.
     */
    public static final Range ORIGIN_RANGE = new Range(1, 1000);

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path fileSystemDirectory;

    private Path fileDirectory = null;
    private Random random;
    private Path recycleBinPath;
    private Path dataDir;

    @BeforeEach
    void beforeEach() throws IOException {
        FileUtils.deleteDirectory(fileSystemDirectory);
        random = getRandomPrintSeed();
        recycleBinPath = fileSystemDirectory.resolve("recycle-bin");
        dataDir = fileSystemDirectory.resolve("data");
        fileDirectory = dataDir.resolve("0");
    }

    @AfterEach
    void afterEach() throws IOException {
        FileUtils.deleteDirectory(fileSystemDirectory);
    }

    static Stream<Arguments> ancientModes() {
        return Stream.of(Arguments.of(GENERATION_THRESHOLD), Arguments.of(BIRTH_ROUND_THRESHOLD));
    }

    @ParameterizedTest
    @MethodSource("ancientModes")
    @DisplayName("Read Files In Order Test")
    void readFilesInOrderTest(@NonNull final AncientMode ancientMode) throws IOException {
        final var pcesFilesGeneratorResult = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                .build()
                .generate();

        final PcesFileTracker fileTracker = PcesFileReader.readFilesFromDisk(
                TestPlatformContexts.context(ancientMode, dataDir, fileSystemDirectory),
                fileDirectory,
                0,
                false,
                ancientMode);

        final List<PcesFile> expectedFiles = pcesFilesGeneratorResult.files();
        assertIteratorEquality(expectedFiles.iterator(), fileTracker.getFileIterator(NO_LOWER_BOUND, 0));

        assertIteratorEquality(
                expectedFiles.iterator(),
                fileTracker.getFileIterator(expectedFiles.getFirst().getUpperBound(), 0));

        // attempt to start a non-existent ancient indicator
        assertIteratorEquality(
                expectedFiles.iterator(), fileTracker.getFileIterator(pcesFilesGeneratorResult.nonExistentValue(), 0));
    }

    @ParameterizedTest
    @MethodSource("ancientModes")
    @DisplayName("Read Files In Order With Gap allowed Test")
    void readFilesInOrderGapTest(@NonNull final AncientMode ancientMode) throws IOException {
        final var pcesFilesGeneratorResult = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                .introduceGapHalfway()
                .build()
                .generate();
        final List<PcesFile> expectedFiles = pcesFilesGeneratorResult.files();

        final PlatformContext platformContext =
                TestPlatformContexts.context(true, ancientMode, dataDir, fileSystemDirectory);

        final PcesFileTracker fileTracker =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, true, ancientMode);
        // Gaps are allowed. We should see all files except for the one that was skipped.
        assertIteratorEquality(expectedFiles.iterator(), fileTracker.getFileIterator(NO_LOWER_BOUND, 0));
    }

    @ParameterizedTest
    @MethodSource("ancientModes")
    @DisplayName("Read Files In Order Gap Not allowed Test")
    void readFilesInOrderGapsNotAllowedTest(@NonNull final AncientMode ancientMode) throws IOException {
        PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                .introduceGapHalfway()
                .build()
                .generate();

        final PlatformContext platformContext =
                TestPlatformContexts.context(false, ancientMode, dataDir, fileSystemDirectory);

        // Gaps are not allowed.
        assertThrows(
                IllegalStateException.class,
                () -> PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, false, ancientMode));
    }

    @ParameterizedTest
    @MethodSource("ancientModes")
    @DisplayName("Read Files From Middle Test")
    void readFilesFromMiddleTest(@NonNull final AncientMode ancientMode) throws IOException {
        final var pcesFilesGeneratorResult = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                .build()
                .generate();
        final List<PcesFile> expectedFiles = pcesFilesGeneratorResult.files();

        final PcesFileTracker fileTracker = PcesFileReader.readFilesFromDisk(
                TestPlatformContexts.context(ancientMode, dataDir, fileSystemDirectory),
                fileDirectory,
                0,
                false,
                ancientMode);

        // For this test, we want to iterate over files so that we are guaranteed to observe every event
        // with an ancient indicator greater than or equal to the target threshold. Choose an ancient indicator
        // that falls roughly in the middle of the sequence of files.
        final long targetAncientIdentifier = (expectedFiles.getFirst().getUpperBound()
                        + expectedFiles.getLast().getUpperBound())
                / 2;

        final List<PcesFile> iteratedFiles = new ArrayList<>();
        fileTracker.getFileIterator(targetAncientIdentifier, 0).forEachRemaining(iteratedFiles::add);

        // Find the index in the file list that was returned first by the iterator
        int indexOfFirstFile = 0;
        for (; indexOfFirstFile < expectedFiles.size(); indexOfFirstFile++) {
            if (expectedFiles.get(indexOfFirstFile).equals(iteratedFiles.getFirst())) {
                break;
            }
        }

        // The file immediately before the returned file should not contain any targeted events
        assertTrue(expectedFiles.get(indexOfFirstFile - 1).getUpperBound() < targetAncientIdentifier);

        // The first file returned from the iterator should
        // have an upper bound greater than or equal to the target ancient indicator.
        assertTrue(iteratedFiles.getFirst().getUpperBound() >= targetAncientIdentifier);

        // Make sure that the iterator returns files in the correct order.
        final List<PcesFile> remainingFiles = new ArrayList<>(iteratedFiles.size());
        for (int index = indexOfFirstFile; index < expectedFiles.size(); index++) {
            remainingFiles.add(expectedFiles.get(index));
        }
        assertIteratorEquality(remainingFiles.iterator(), iteratedFiles.iterator());
    }

    /**
     * Similar to the other test that starts iteration in the middle, except that files will have the same bounds with
     * high probability. Not a scenario we are likely to encounter in production, but it's a tricky edge case we need to
     * handle elegantly.
     */
    @ParameterizedTest
    @MethodSource("ancientModes")
    @DisplayName("Read Files From Middle Repeating Boundaries Test")
    void readFilesFromMiddleRepeatingBoundariesTest(@NonNull final AncientMode ancientMode) throws IOException {
        final var pcesFilesGeneratorResult = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                // Advance the bounds only 10% of the time
                .withAdvanceBoundsStrategy(i -> random.nextLong() < 0.1)
                .build()
                .generate();
        final List<PcesFile> expectedFiles = pcesFilesGeneratorResult.files();

        final PcesFileTracker fileTracker = PcesFileReader.readFilesFromDisk(
                TestPlatformContexts.context(ancientMode, dataDir, fileSystemDirectory),
                fileDirectory,
                0,
                false,
                ancientMode);

        // For this test, we want to iterate over files so that we are guaranteed to observe every event
        // with an ancient indicator greater than or equal to the target. Choose an ancient indicator that falls
        // roughly in the middle of the sequence of files.
        final long targetAncientIdentifier = (expectedFiles.getFirst().getUpperBound()
                        + expectedFiles.getLast().getUpperBound())
                / 2;

        final List<PcesFile> iteratedFiles = new ArrayList<>();
        fileTracker.getFileIterator(targetAncientIdentifier, 0).forEachRemaining(iteratedFiles::add);

        // Find the index in the file list that was returned first by the iterator
        int indexOfFirstFile = 0;
        for (; indexOfFirstFile < expectedFiles.size(); indexOfFirstFile++) {
            if (expectedFiles.get(indexOfFirstFile).equals(iteratedFiles.getFirst())) {
                break;
            }
        }

        // The file immediately before the returned file should not contain any targeted events
        assertTrue(expectedFiles.get(indexOfFirstFile - 1).getUpperBound() < targetAncientIdentifier);

        // The first file returned from the iterator should
        // have an upper bound greater than or equal to the target ancient indicator.
        assertTrue(iteratedFiles.getFirst().getUpperBound() >= targetAncientIdentifier);

        // Make sure that the iterator returns files in the correct order.
        final List<PcesFile> remainingFiles = new ArrayList<>(iteratedFiles.size());
        for (int index = indexOfFirstFile; index < expectedFiles.size(); index++) {
            remainingFiles.add(expectedFiles.get(index));
        }
        assertIteratorEquality(remainingFiles.iterator(), iteratedFiles.iterator());
    }

    @ParameterizedTest
    @MethodSource("ancientModes")
    @DisplayName("Read Files From High ancient indicator Test")
    void readFilesFromHighAncientIdentifierTest(@NonNull final AncientMode ancientMode) throws IOException {
        final var pcesFilesGeneratorResult = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                .build()
                .generate();
        final List<PcesFile> expectedFiles = pcesFilesGeneratorResult.files();

        final PcesFileTracker fileTracker = PcesFileReader.readFilesFromDisk(
                TestPlatformContexts.context(ancientMode, dataDir, fileSystemDirectory),
                fileDirectory,
                0,
                false,
                ancientMode);

        // Request an ancient indicator higher than all files in the data store
        final long targetAncientIdentifier = expectedFiles.getLast().getUpperBound() + 1;

        final Iterator<PcesFile> iterator = fileTracker.getFileIterator(targetAncientIdentifier, 0);
        assertFalse(iterator.hasNext());
    }

    @ParameterizedTest
    @MethodSource("ancientModes")
    @DisplayName("Read Files From Empty Stream Test")
    void readFilesFromEmptyStreamTest(@NonNull final AncientMode ancientMode) {
        assertThrows(
                NoSuchFileException.class,
                () -> PcesFileReader.readFilesFromDisk(
                        TestPlatformContexts.context(ancientMode, dataDir, fileSystemDirectory),
                        fileDirectory,
                        0,
                        false,
                        ancientMode));
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
    @MethodSource("ancientModes")
    @DisplayName("Start And First File Discontinuity In Middle Test")
    void startAtFirstFileDiscontinuityInMiddleTest(@NonNull final AncientMode ancientMode) throws IOException {
        final var pcesFilesGenerator = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                .discontinue()
                .withOriginRange(ORIGIN_RANGE)
                .build()
                .generate();

        final PlatformContext platformContext =
                TestPlatformContexts.context(false, ancientMode, recycleBinPath, dataDir, fileSystemDirectory);
        // Scenario 1: choose an origin that lands on the resultingUnbrokenOrigin exactly.
        final PcesFileTracker fileTracker1 = PcesFileReader.readFilesFromDisk(
                platformContext, fileDirectory, pcesFilesGenerator.resultingUnbrokenOrigin(), false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesAfterDiscontinuity().iterator(),
                fileTracker1.getFileIterator(NO_LOWER_BOUND, pcesFilesGenerator.resultingUnbrokenOrigin()));

        // Scenario 2: choose an origin that lands after the resultingUnbrokenOrigin.
        final long startingRound2 = pcesFilesGenerator.pointAfterUnbrokenOrigin();
        final PcesFileTracker fileTracker2 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound2, false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesAfterDiscontinuity().iterator(),
                fileTracker2.getFileIterator(NO_LOWER_BOUND, startingRound2));

        // Scenario 3: choose an origin that comes before the resultingUnbrokenOrigin. This will cause
        // the files
        // after the origin to be deleted.
        final long startingRound3 = pcesFilesGenerator.pointBeforeUnbrokenOrigin();
        final PcesFileTracker fileTracker3 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound3, false, ancientMode);

        assertIteratorEquality(
                pcesFilesGenerator.filesBeforeDiscontinuity().iterator(),
                fileTracker3.getFileIterator(NO_LOWER_BOUND, startingRound3));

        final List<PcesFile> expectedFiles = pcesFilesGenerator.files();
        validateRecycledFiles(pcesFilesGenerator.filesBeforeDiscontinuity(), expectedFiles, this.recycleBinPath);

        // Scenario 4: choose an origin that is incompatible with all state files. This will cause all
        // remaining
        // files to be deleted.
        final long startingRound4 = ORIGIN_RANGE.start() - 1;
        final PcesFileTracker fileTracker4 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound4, false, ancientMode);

        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker4.getFileIterator(NO_LOWER_BOUND, startingRound4));

        validateRecycledFiles(List.of(), expectedFiles, this.recycleBinPath);
    }

    /**
     * In this test, a discontinuity is placed in the middle of the stream. We begin iterating at a file that comes
     * before the discontinuity, but it isn't the first file in the stream.
     */
    @ParameterizedTest
    @MethodSource("ancientModes")
    @DisplayName("Start At Middle File Discontinuity In Middle Test")
    void startAtMiddleFileDiscontinuityInMiddleTest(@NonNull final AncientMode ancientMode) throws IOException {
        final var pcesFilesGenerator = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                .skipAtStart()
                .discontinue()
                .withOriginRange(ORIGIN_RANGE)
                .build()
                .generate();

        // Note that the file at index 0 is not the first file in the stream,
        // but it is the first file we want to iterate
        final List<PcesFile> expectedFiles = pcesFilesGenerator.files();
        final long startAncientIdentifier = expectedFiles.getFirst().getUpperBound();

        final PlatformContext platformContext =
                TestPlatformContexts.context(false, ancientMode, recycleBinPath, dataDir, fileSystemDirectory);

        // Scenario 1: choose an origin that lands on the resultingUnbrokenOrigin exactly.
        final long startingRound1 = pcesFilesGenerator.resultingUnbrokenOrigin();
        final PcesFileTracker fileTracker1 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound1, false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesAfterDiscontinuity().iterator(),
                fileTracker1.getFileIterator(startAncientIdentifier, startingRound1));

        // Scenario 2: choose an origin that lands after the resultingUnbrokenOrigin.
        final long startingRound2 = pcesFilesGenerator.pointAfterUnbrokenOrigin();
        final PcesFileTracker fileTracker2 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound2, false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesAfterDiscontinuity().iterator(),
                fileTracker2.getFileIterator(startAncientIdentifier, startingRound2));

        // Scenario 3: choose an origin that comes before the resultingUnbrokenOrigin. This will cause
        // the files
        // after the origin to be deleted.
        final long startingRound3 = pcesFilesGenerator.pointBeforeUnbrokenOrigin();
        final PcesFileTracker fileTracker3 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound3, false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesBeforeDiscontinuity().iterator(),
                fileTracker3.getFileIterator(startAncientIdentifier, startingRound3));

        validateRecycledFiles(pcesFilesGenerator.filesBeforeDiscontinuity(), expectedFiles, this.recycleBinPath);

        // Scenario 4: choose an origin that is incompatible with all state files. This will cause all
        // remaining
        // files to be deleted.
        final long startingRound4 = ORIGIN_RANGE.start() - 1;
        final PcesFileTracker fileTracker4 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound4, false, ancientMode);
        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker4.getFileIterator(startAncientIdentifier, startingRound4));

        validateRecycledFiles(List.of(), expectedFiles, this.recycleBinPath);
    }

    /**
     * In this test, a discontinuity is placed in the middle of the stream, and we begin iterating on that exact file.
     */
    @ParameterizedTest
    @MethodSource("ancientModes")
    @DisplayName("Start At Middle File Discontinuity In Middle Test")
    void startAtDiscontinuityInMiddleTest(@NonNull final AncientMode ancientMode) throws IOException {
        final var pcesFilesGenerator = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                .discontinue()
                .withOriginRange(ORIGIN_RANGE)
                .build()
                .generate();

        // Note that the file at index 0 is not the first file in the stream,
        // but it is the first file we want to iterate
        final long startAncientIdentifier =
                pcesFilesGenerator.filesAfterDiscontinuity().getFirst().getUpperBound();

        final PlatformContext platformContext =
                TestPlatformContexts.context(false, ancientMode, recycleBinPath, dataDir, fileSystemDirectory);
        // Scenario 1: choose an origin that lands on the resultingUnbrokenOrigin exactly.
        final long startingRound1 = pcesFilesGenerator.resultingUnbrokenOrigin();
        final PcesFileTracker fileTracker1 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound1, false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesAfterDiscontinuity().iterator(),
                fileTracker1.getFileIterator(startAncientIdentifier, startingRound1));

        // Scenario 2: choose an origin that lands after the resultingUnbrokenOrigin.
        final long startingRound2 = pcesFilesGenerator.pointAfterUnbrokenOrigin();
        final PcesFileTracker fileTracker2 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound2, false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesAfterDiscontinuity().iterator(),
                fileTracker2.getFileIterator(startAncientIdentifier, startingRound2));

        // Scenario 3: choose an origin that comes before the resultingUnbrokenOrigin. This will cause
        // the files
        // after the origin to be deleted.
        final long startingRound3 = pcesFilesGenerator.pointBeforeUnbrokenOrigin();
        final PcesFileTracker fileTracker3 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound3, false, ancientMode);
        // There is no files with a compatible origin and events with ancient indicators in the span we
        // want.
        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker3.getFileIterator(startAncientIdentifier, startingRound3));

        final List<PcesFile> expectedFiles = pcesFilesGenerator.files();
        validateRecycledFiles(pcesFilesGenerator.filesBeforeDiscontinuity(), expectedFiles, this.recycleBinPath);

        // Scenario 4: choose an origin that is incompatible with all state files. This will cause all
        // remaining
        // files to be deleted.
        final long startingRound4 = ORIGIN_RANGE.start() - 1;
        final PcesFileTracker fileTracker4 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound4, false, ancientMode);
        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker4.getFileIterator(startAncientIdentifier, startingRound4));

        validateRecycledFiles(List.of(), expectedFiles, this.recycleBinPath);
    }

    /**
     * In this test, a discontinuity is placed in the middle of the stream, and we begin iterating after that file.
     */
    @ParameterizedTest
    @MethodSource("ancientModes")
    @DisplayName("Start After Discontinuity In Middle Test")
    void startAfterDiscontinuityInMiddleTest(@NonNull final AncientMode ancientMode) throws IOException {

        final var pcesFilesGenerator = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                .discontinue()
                .withOriginRange(ORIGIN_RANGE)
                .build()
                .generate();
        // Note that the file at index 0 is not the first file in the stream,
        // but it is the first file we want to iterate
        final long startAncientBoundary =
                pcesFilesGenerator.filesAfterDiscontinuity().getFirst().getUpperBound();

        final PlatformContext platformContext =
                TestPlatformContexts.context(false, ancientMode, recycleBinPath, dataDir, fileSystemDirectory);

        // Scenario 1: choose an origin that lands on the resultingUnbrokenOrigin exactly.
        final long startingRound1 = pcesFilesGenerator.resultingUnbrokenOrigin();
        final PcesFileTracker fileTracker1 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound1, false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesAfterDiscontinuity().iterator(),
                fileTracker1.getFileIterator(startAncientBoundary, startingRound1));

        // Scenario 2: choose an origin that lands after the resultingUnbrokenOrigin.
        final long startingRound2 = pcesFilesGenerator.pointAfterUnbrokenOrigin();
        final PcesFileTracker fileTracker2 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound2, false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesAfterDiscontinuity().iterator(),
                fileTracker2.getFileIterator(startAncientBoundary, startingRound2));

        // Scenario 3: choose an origin that comes before the resultingUnbrokenOrigin. This will cause
        // the files
        // after the origin to be deleted.
        final long startingRound3 = pcesFilesGenerator.pointBeforeUnbrokenOrigin();
        final PcesFileTracker fileTracker3 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound3, false, ancientMode);
        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker3.getFileIterator(startAncientBoundary, startingRound3));

        final List<PcesFile> expectedFiles = pcesFilesGenerator.files();
        validateRecycledFiles(pcesFilesGenerator.filesBeforeDiscontinuity(), expectedFiles, this.recycleBinPath);

        // Scenario 4: choose an origin that is incompatible with all state files. This will cause all
        // remaining
        // files to be deleted.
        final long startingRound4 = ORIGIN_RANGE.start() - 1;
        final PcesFileTracker fileTracker4 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound4, false, ancientMode);
        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker4.getFileIterator(startAncientBoundary, startingRound4));

        validateRecycledFiles(List.of(), expectedFiles, this.recycleBinPath);
    }

    @Test
    void readFilesOfBothTypesTest() throws IOException {
        final var generationPcesFilesGenerator = PcesTestFilesGenerator.Builder.create(
                        GENERATION_THRESHOLD, random, fileDirectory)
                .build()
                .generate();
        final var birthroundPcesFilesGenerator = PcesTestFilesGenerator.Builder.create(
                        BIRTH_ROUND_THRESHOLD, random, fileDirectory)
                .build()
                .generate();

        final List<PcesFile> generationFiles = generationPcesFilesGenerator.files();

        // Phase 2: write files using birth rounds
        final List<PcesFile> birthRoundFiles = birthroundPcesFilesGenerator.files();
        final PcesFileTracker generationFileTracker = PcesFileReader.readFilesFromDisk(
                TestPlatformContexts.context(GENERATION_THRESHOLD, dataDir, fileSystemDirectory),
                fileDirectory,
                0,
                false,
                GENERATION_THRESHOLD);

        final PcesFileTracker birthRoundFileTracker = PcesFileReader.readFilesFromDisk(
                TestPlatformContexts.context(BIRTH_ROUND_THRESHOLD, dataDir, fileSystemDirectory),
                fileDirectory,
                0,
                false,
                BIRTH_ROUND_THRESHOLD);

        assertIteratorEquality(generationFiles.iterator(), generationFileTracker.getFileIterator(NO_LOWER_BOUND, 0));
        assertIteratorEquality(birthRoundFiles.iterator(), birthRoundFileTracker.getFileIterator(NO_LOWER_BOUND, 0));
    }

    /**
     * When fixing an origin, invalid files are moved to a "recycle bin" directory.
     * This method validates that behavior.
     */
    static void validateRecycledFiles(
            @NonNull final List<PcesFile> filesThatShouldBePresent,
            @NonNull final List<PcesFile> allFiles,
            final Path recycleBinPath)
            throws IOException {

        final Set<Path> recycledFiles = new HashSet<>();
        try (final Stream<Path> stream = Files.walk(recycleBinPath)) {
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
}
