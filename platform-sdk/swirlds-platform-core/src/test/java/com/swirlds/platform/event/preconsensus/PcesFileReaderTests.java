// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertIteratorEquality;
import static com.swirlds.common.test.fixtures.RandomUtils.getRandomPrintSeed;
import static com.swirlds.platform.event.preconsensus.PcesFileManager.NO_LOWER_BOUND;
import static org.hiero.consensus.model.event.AncientMode.BIRTH_ROUND_THRESHOLD;
import static org.hiero.consensus.model.event.AncientMode.GENERATION_THRESHOLD;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.FileUtils;
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
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path fileSystemDirectory;

    private Path fileDirectory = null;

    private Random random;

    private Path recycleBinPath;
    private Path dataDir;

    /**
     * When fixing a resultingUnbrokenOrigin, invalid files are moved to a "recycle bin" directory. This method validates that
     * behavior.
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

    protected static Stream<Arguments> buildArguments() {
        return Stream.of(Arguments.of(GENERATION_THRESHOLD), Arguments.of(BIRTH_ROUND_THRESHOLD));
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Read Files In Order Test")
    void readFilesInOrderTest(@NonNull final AncientMode ancientMode) throws IOException {
        final var pcesFilesGeneratorResult = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                .build()
                .generate();

        final PcesFileTracker fileTracker = PcesFileReader.readFilesFromDisk(
                TestPlatformContextFactories.context(ancientMode, dataDir, fileSystemDirectory),
                fileDirectory,
                0,
                false,
                ancientMode);

        assertIteratorEquality(
                pcesFilesGeneratorResult.files().iterator(), fileTracker.getFileIterator(NO_LOWER_BOUND, 0));

        assertIteratorEquality(
                pcesFilesGeneratorResult.files().iterator(),
                fileTracker.getFileIterator(
                        pcesFilesGeneratorResult.files().getFirst().getUpperBound(), 0));

        // attempt to start a non-existent ancient indicator
        assertIteratorEquality(
                pcesFilesGeneratorResult.files().iterator(),
                fileTracker.getFileIterator(pcesFilesGeneratorResult.nonExistentValue(), 0));
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Read Files In Order Gap Test")
    void readFilesInOrderGapTest(@NonNull final AncientMode ancientMode) throws IOException {
        for (final boolean permitGaps : List.of(true, false)) {
            final var pcesFilesGeneratorResult = PcesTestFilesGenerator.Builder.create(
                            ancientMode, random, fileDirectory)
                    .skipElementAtHalf()
                    .build()
                    .generate();
            final List<PcesFile> files = pcesFilesGeneratorResult.files();

            final PlatformContext platformContext =
                    TestPlatformContextFactories.context(permitGaps, ancientMode, dataDir, fileSystemDirectory);

            if (permitGaps) {
                final PcesFileTracker fileTracker =
                        PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, true, ancientMode);
                // Gaps are allowed. We should see all files except for the one that was skipped.
                assertIteratorEquality(files.iterator(), fileTracker.getFileIterator(NO_LOWER_BOUND, 0));
            } else {
                // Gaps are not allowed.
                assertThrows(
                        IllegalStateException.class,
                        () -> PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, 0, false, ancientMode));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Read Files From Middle Test")
    void readFilesFromMiddleTest(@NonNull final AncientMode ancientMode) throws IOException {
        final var pcesFilesGeneratorResult = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                .build()
                .generate();
        final List<PcesFile> files = pcesFilesGeneratorResult.files();

        final PcesFileTracker fileTracker = PcesFileReader.readFilesFromDisk(
                TestPlatformContextFactories.context(ancientMode, dataDir, fileSystemDirectory),
                fileDirectory,
                0,
                false,
                ancientMode);

        // For this test, we want to iterate over files so that we are guaranteed to observe every event
        // with an ancient indicator greater than or equal to the target threshold. Choose an ancient indicator
        // that falls roughly in the middle of the sequence of files.
        final long targetAncientIdentifier = (files.getFirst().getUpperBound()
                        + files.get(PcesTestFilesGenerator.FILE_COUNT - 1).getUpperBound())
                / 2;

        final List<PcesFile> iteratedFiles = new ArrayList<>();
        fileTracker.getFileIterator(targetAncientIdentifier, 0).forEachRemaining(iteratedFiles::add);

        // Find the index in the file list that was returned first by the iterator
        int indexOfFirstFile = 0;
        for (; indexOfFirstFile < PcesTestFilesGenerator.FILE_COUNT; indexOfFirstFile++) {
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
        for (int index = indexOfFirstFile; index < PcesTestFilesGenerator.FILE_COUNT; index++) {
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
        final var pcesFilesGeneratorResult = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                // Advance the bounds only 10% of the time
                .withAdvanceBoundsStrategy(i -> random.nextLong() < 0.1)
                .build()
                .generate();
        final List<PcesFile> files = pcesFilesGeneratorResult.files();

        final PcesFileTracker fileTracker = PcesFileReader.readFilesFromDisk(
                TestPlatformContextFactories.context(ancientMode, dataDir, fileSystemDirectory),
                fileDirectory,
                0,
                false,
                ancientMode);

        // For this test, we want to iterate over files so that we are guaranteed to observe every event
        // with an ancient indicator greater than or equal to the target. Choose an ancient indicator that falls
        // roughly in the middle of the sequence of files.
        final long targetAncientIdentifier = (files.getFirst().getUpperBound()
                        + files.get(PcesTestFilesGenerator.FILE_COUNT - 1).getUpperBound())
                / 2;

        final List<PcesFile> iteratedFiles = new ArrayList<>();
        fileTracker.getFileIterator(targetAncientIdentifier, 0).forEachRemaining(iteratedFiles::add);

        // Find the index in the file list that was returned first by the iterator
        int indexOfFirstFile = 0;
        for (; indexOfFirstFile < PcesTestFilesGenerator.FILE_COUNT; indexOfFirstFile++) {
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
        for (int index = indexOfFirstFile; index < PcesTestFilesGenerator.FILE_COUNT; index++) {
            expectedFiles.add(files.get(index));
        }
        assertIteratorEquality(expectedFiles.iterator(), iteratedFiles.iterator());
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Read Files From High ancient indicator Test")
    void readFilesFromHighAncientIdentifierTest(@NonNull final AncientMode ancientMode) throws IOException {
        final var pcesFilesGeneratorResult = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                .build()
                .generate();
        final List<PcesFile> files = pcesFilesGeneratorResult.files();

        final PcesFileTracker fileTracker = PcesFileReader.readFilesFromDisk(
                TestPlatformContextFactories.context(ancientMode, dataDir, fileSystemDirectory),
                fileDirectory,
                0,
                false,
                ancientMode);

        // Request an ancient indicator higher than all files in the data store
        final long targetAncientIdentifier =
                files.get(PcesTestFilesGenerator.FILE_COUNT - 1).getUpperBound() + 1;

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
                        TestPlatformContextFactories.context(ancientMode, dataDir, fileSystemDirectory),
                        fileDirectory,
                        0,
                        false,
                        ancientMode));
    }

    /**
     *  Given that allowing gaps or discontinuities in the resultingUnbrokenOrigin block of the PcesFile is likely to either lead to ISSes or, more likely, cause
     *  events to be added to the hashgraph without their parents being added,
     * the aim of the test is asserting that readFilesFromDisk is able to detect gaps or discontinuities exist in the existing PcesFiles.
     * </br>
     * This test, generates a list of files PcesFiles and places a discontinuity in the resultingUnbrokenOrigin block randomly in the list.
     * The sequence numbers are intentionally picked close to wrapping around the 3 digit to 4 digit, to cause the files not to line up
     * alphabetically, and test the code support for that.
     * The scenarios under test are:
     *  * readFilesFromDisk is asked to read at the discontinuity resultingUnbrokenOrigin block
     *  * readFilesFromDisk is asked to read after the discontinuity resultingUnbrokenOrigin block
     *  * readFilesFromDisk is asked to read before the discontinuity resultingUnbrokenOrigin block
     *  * readFilesFromDisk is asked to read a non-existent resultingUnbrokenOrigin block
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Start And First File Discontinuity In Middle Test")
    void startAtFirstFileDiscontinuityInMiddleTest(@NonNull final AncientMode ancientMode) throws IOException {
        final var pcesFilesGenerator = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                .discontinue()
                .withDefaultOriginRange()
                .build()
                .generate();

        final PlatformContext platformContext =
                TestPlatformContextFactories.context(false, ancientMode, recycleBinPath, dataDir, fileSystemDirectory);
        // Scenario 1: choose an resultingUnbrokenOrigin that lands on the resultingUnbrokenOrigin exactly.
        final PcesFileTracker fileTracker1 = PcesFileReader.readFilesFromDisk(
                platformContext, fileDirectory, pcesFilesGenerator.resultingUnbrokenOrigin(), false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesAfterDiscontinuity().iterator(),
                fileTracker1.getFileIterator(NO_LOWER_BOUND, pcesFilesGenerator.resultingUnbrokenOrigin()));

        // Scenario 2: choose an resultingUnbrokenOrigin that lands after the resultingUnbrokenOrigin.
        final long startingRound2 = pcesFilesGenerator.getPointAfterUnbrokenOrigin();
        final PcesFileTracker fileTracker2 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound2, false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesAfterDiscontinuity().iterator(),
                fileTracker2.getFileIterator(NO_LOWER_BOUND, startingRound2));

        // Scenario 3: choose an resultingUnbrokenOrigin that comes before the resultingUnbrokenOrigin. This will cause
        // the files
        // after the resultingUnbrokenOrigin to be deleted.
        final long startingRound3 = pcesFilesGenerator.getPointBeforeUnbrokenOrigin();
        final PcesFileTracker fileTracker3 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound3, false, ancientMode);

        assertIteratorEquality(
                pcesFilesGenerator.filesBeforeDiscontinuity().iterator(),
                fileTracker3.getFileIterator(NO_LOWER_BOUND, startingRound3));

        validateRecycledFiles(
                pcesFilesGenerator.filesBeforeDiscontinuity(), pcesFilesGenerator.files(), this.recycleBinPath);

        // Scenario 4: choose an resultingUnbrokenOrigin that is incompatible with all state files. This will cause all
        // remaining
        // files to be deleted.
        final long startingRound4 = 0;
        final PcesFileTracker fileTracker4 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound4, false, ancientMode);

        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker4.getFileIterator(NO_LOWER_BOUND, startingRound4));

        validateRecycledFiles(List.of(), pcesFilesGenerator.files(), this.recycleBinPath);
    }

    /**
     * In this test, a discontinuity is placed in the middle of the stream. We begin iterating at a file that comes
     * before the discontinuity, but it isn't the first file in the stream.
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Start At Middle File Discontinuity In Middle Test")
    void startAtMiddleFileDiscontinuityInMiddleTest(@NonNull final AncientMode ancientMode) throws IOException {
        final var pcesFilesGenerator = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                .skipSomeAtStart()
                .discontinue()
                .withDefaultOriginRange()
                .build()
                .generate();

        // Note that the file at index 0 is not the first file in the stream,
        // but it is the first file we want to iterate
        final long startAncientIdentifier =
                pcesFilesGenerator.files().getFirst().getUpperBound();

        final PlatformContext platformContext = TestPlatformContextFactories.context(
                false, ancientMode, fileSystemDirectory.resolve("recycle-bin"), dataDir, fileSystemDirectory);

        // Scenario 1: choose an resultingUnbrokenOrigin that lands on the resultingUnbrokenOrigin exactly.
        final long startingRound1 = pcesFilesGenerator.resultingUnbrokenOrigin();
        final PcesFileTracker fileTracker1 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound1, false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesAfterDiscontinuity().iterator(),
                fileTracker1.getFileIterator(startAncientIdentifier, startingRound1));

        // Scenario 2: choose an resultingUnbrokenOrigin that lands after the resultingUnbrokenOrigin.
        final long startingRound2 = pcesFilesGenerator.getPointAfterUnbrokenOrigin();
        final PcesFileTracker fileTracker2 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound2, false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesAfterDiscontinuity().iterator(),
                fileTracker2.getFileIterator(startAncientIdentifier, startingRound2));

        // Scenario 3: choose an resultingUnbrokenOrigin that comes before the resultingUnbrokenOrigin. This will cause
        // the files
        // after the resultingUnbrokenOrigin to be deleted.
        final long startingRound3 = pcesFilesGenerator.getPointBeforeUnbrokenOrigin();
        final PcesFileTracker fileTracker3 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound3, false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesBeforeDiscontinuity().iterator(),
                fileTracker3.getFileIterator(startAncientIdentifier, startingRound3));

        validateRecycledFiles(
                pcesFilesGenerator.filesBeforeDiscontinuity(), pcesFilesGenerator.files(), this.recycleBinPath);

        // Scenario 4: choose an resultingUnbrokenOrigin that is incompatible with all state files. This will cause all
        // remaining
        // files to be deleted.
        final long startingRound4 = 0;
        final PcesFileTracker fileTracker4 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound4, false, ancientMode);
        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker4.getFileIterator(startAncientIdentifier, startingRound4));

        validateRecycledFiles(List.of(), pcesFilesGenerator.files(), this.recycleBinPath);
    }

    /**
     * In this test, a discontinuity is placed in the middle of the stream, and we begin iterating on that exact file.
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Start At Middle File Discontinuity In Middle Test")
    void startAtDiscontinuityInMiddleTest(@NonNull final AncientMode ancientMode) throws IOException {
        final var pcesFilesGenerator = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                .discontinue()
                .withDefaultOriginRange()
                .build()
                .generate();

        // Note that the file at index 0 is not the first file in the stream,
        // but it is the first file we want to iterate
        final long startAncientIdentifier =
                pcesFilesGenerator.filesAfterDiscontinuity().getFirst().getUpperBound();

        final PlatformContext platformContext = TestPlatformContextFactories.context(
                false, ancientMode, fileSystemDirectory.resolve("recycle-bin"), dataDir, fileSystemDirectory);
        // Scenario 1: choose an resultingUnbrokenOrigin that lands on the resultingUnbrokenOrigin exactly.
        final long startingRound1 = pcesFilesGenerator.resultingUnbrokenOrigin();
        final PcesFileTracker fileTracker1 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound1, false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesAfterDiscontinuity().iterator(),
                fileTracker1.getFileIterator(startAncientIdentifier, startingRound1));

        // Scenario 2: choose an resultingUnbrokenOrigin that lands after the resultingUnbrokenOrigin.
        final long startingRound2 = pcesFilesGenerator.getPointAfterUnbrokenOrigin();
        final PcesFileTracker fileTracker2 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound2, false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesAfterDiscontinuity().iterator(),
                fileTracker2.getFileIterator(startAncientIdentifier, startingRound2));

        // Scenario 3: choose an resultingUnbrokenOrigin that comes before the resultingUnbrokenOrigin. This will cause
        // the files
        // after the resultingUnbrokenOrigin to be deleted.
        final long startingRound3 = pcesFilesGenerator.getPointBeforeUnbrokenOrigin();
        final PcesFileTracker fileTracker3 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound3, false, ancientMode);
        // There is no files with a compatible resultingUnbrokenOrigin and events with ancient indicators in the span we
        // want.
        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker3.getFileIterator(startAncientIdentifier, startingRound3));

        validateRecycledFiles(
                pcesFilesGenerator.filesBeforeDiscontinuity(), pcesFilesGenerator.files(), this.recycleBinPath);

        // Scenario 4: choose an resultingUnbrokenOrigin that is incompatible with all state files. This will cause all
        // remaining
        // files to be deleted.
        final long startingRound4 = 0;
        final PcesFileTracker fileTracker4 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound4, false, ancientMode);
        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker4.getFileIterator(startAncientIdentifier, startingRound4));

        validateRecycledFiles(List.of(), pcesFilesGenerator.files(), this.recycleBinPath);
    }

    /**
     * In this test, a discontinuity is placed in the middle of the stream, and we begin iterating after that file.
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Start After Discontinuity In Middle Test")
    void startAfterDiscontinuityInMiddleTest(@NonNull final AncientMode ancientMode) throws IOException {

        final var pcesFilesGenerator = PcesTestFilesGenerator.Builder.create(ancientMode, random, fileDirectory)
                .discontinue()
                .withDefaultOriginRange()
                .build()
                .generate();
        // Note that the file at index 0 is not the first file in the stream,
        // but it is the first file we want to iterate
        final long startAncientBoundary =
                pcesFilesGenerator.filesAfterDiscontinuity().getFirst().getUpperBound();

        final PlatformContext platformContext = TestPlatformContextFactories.context(
                false, ancientMode, fileSystemDirectory.resolve("recycle-bin"), dataDir, fileSystemDirectory);

        // Scenario 1: choose an resultingUnbrokenOrigin that lands on the resultingUnbrokenOrigin exactly.
        final long startingRound1 = pcesFilesGenerator.resultingUnbrokenOrigin();
        final PcesFileTracker fileTracker1 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound1, false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesAfterDiscontinuity().iterator(),
                fileTracker1.getFileIterator(startAncientBoundary, startingRound1));

        // Scenario 2: choose an resultingUnbrokenOrigin that lands after the resultingUnbrokenOrigin.
        final long startingRound2 = pcesFilesGenerator.getPointAfterUnbrokenOrigin();
        final PcesFileTracker fileTracker2 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound2, false, ancientMode);
        assertIteratorEquality(
                pcesFilesGenerator.filesAfterDiscontinuity().iterator(),
                fileTracker2.getFileIterator(startAncientBoundary, startingRound2));

        // Scenario 3: choose an resultingUnbrokenOrigin that comes before the resultingUnbrokenOrigin. This will cause
        // the files
        // after the resultingUnbrokenOrigin to be deleted.
        final long startingRound3 = pcesFilesGenerator.getPointBeforeUnbrokenOrigin();
        final PcesFileTracker fileTracker3 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound3, false, ancientMode);
        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker3.getFileIterator(startAncientBoundary, startingRound3));

        validateRecycledFiles(
                pcesFilesGenerator.filesBeforeDiscontinuity(), pcesFilesGenerator.files(), this.recycleBinPath);

        // Scenario 4: choose an resultingUnbrokenOrigin that is incompatible with all state files. This will cause all
        // remaining
        // files to be deleted.
        final long startingRound4 = 0;
        final PcesFileTracker fileTracker4 =
                PcesFileReader.readFilesFromDisk(platformContext, fileDirectory, startingRound4, false, ancientMode);
        assertIteratorEquality(
                Collections.emptyIterator(), fileTracker4.getFileIterator(startAncientBoundary, startingRound4));

        validateRecycledFiles(List.of(), pcesFilesGenerator.files(), this.recycleBinPath);
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
                TestPlatformContextFactories.context(GENERATION_THRESHOLD, dataDir, fileSystemDirectory),
                fileDirectory,
                0,
                false,
                GENERATION_THRESHOLD);

        final PcesFileTracker birthRoundFileTracker = PcesFileReader.readFilesFromDisk(
                TestPlatformContextFactories.context(BIRTH_ROUND_THRESHOLD, dataDir, fileSystemDirectory),
                fileDirectory,
                0,
                false,
                BIRTH_ROUND_THRESHOLD);

        assertIteratorEquality(generationFiles.iterator(), generationFileTracker.getFileIterator(NO_LOWER_BOUND, 0));
        assertIteratorEquality(birthRoundFiles.iterator(), birthRoundFileTracker.getFileIterator(NO_LOWER_BOUND, 0));
    }
}
