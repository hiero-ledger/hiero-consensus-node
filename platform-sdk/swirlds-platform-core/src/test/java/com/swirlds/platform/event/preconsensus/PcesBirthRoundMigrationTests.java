// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.platform.event.preconsensus.PcesBirthRoundMigration.findPcesFiles;
import static org.hiero.consensus.model.event.AncientMode.BIRTH_ROUND_THRESHOLD;
import static org.hiero.consensus.model.event.AncientMode.GENERATION_THRESHOLD;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.randomInstant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.common.io.utility.RecycleBinImpl;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.test.fixtures.TestRecycleBin;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.common.threading.manager.AdHocThreadManager;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.StandardEventSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PcesBirthRoundMigrationTests {

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    private Path testDirectory;

    private Path recycleBinPath;
    private Path pcesPath;
    private Path temporaryFilePath;

    @BeforeEach
    void beforeEach() throws IOException {
        if (Files.exists(testDirectory)) {
            FileUtils.deleteDirectory(testDirectory);
        }

        Files.createDirectories(testDirectory);
        recycleBinPath = testDirectory.resolve("recycle-bin");
        pcesPath = testDirectory.resolve("pces");
        temporaryFilePath = testDirectory.resolve("tmp");
    }

    @AfterEach
    void afterEach() throws IOException {
        if (Files.exists(testDirectory)) {
            FileUtils.deleteDirectory(testDirectory);
        }
    }

    /**
     * Describes the PCES stream that was written by {@link #generateLegacyPcesFiles(Random, DiscontinuityType)}
     *
     * @param files                  a list of files that were written
     * @param events                 a list of all events in the stream, if there is a discontinuity then only include
     *                               events after the discontinuity
     */
    private record PcesFilesWritten(@NonNull List<PcesFile> files, @NonNull List<PlatformEvent> events) {}

    /**
     * Generate a bunch of PCES files in the legacy format.
     *
     * @param random            the random number generator
     * @param discontinuityType whether to introduce a discontinuity in the stream and the type of the discontinuity
     * @return a list of events that were written to disk
     */
    @NonNull
    private PcesFilesWritten generateLegacyPcesFiles(
            @NonNull final Random random, final DiscontinuityType discontinuityType) throws IOException {

        final int eventCount = 1000;
        final int fileCount = 10;
        final int eventsPerFile = eventCount / fileCount;
        final Instant startingTime = randomInstant(random);

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final StandardGraphGenerator generator = new StandardGraphGenerator(
                platformContext,
                random.nextLong(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource());

        final List<PlatformEvent> events = new ArrayList<>();
        for (int i = 0; i < eventCount; i++) {
            events.add(generator.generateEvent().getBaseEvent());
        }
        events.sort(Comparator.comparingLong(PlatformEvent::getGeneration));

        final Path fullPcesPath = pcesPath.resolve("0");

        final List<PcesFile> files = new ArrayList<>();
        long origin = 0;
        boolean discontinutiyIntroduced = false;
        final List<PlatformEvent> postDiscontinuityEvents = new ArrayList<>();
        for (int fileIndex = 0; fileIndex < fileCount; fileIndex++) {

            if (discontinuityType == DiscontinuityType.IN_EVENTS_THAT_ARE_NOT_MIGRATED && fileIndex == fileCount / 3) {
                // introduce a discontinuity in events that are not migrated.
                // Events after the 1/2 way point are migrated, so we won't be to
                // events that we are going to migrate yet.
                origin = random.nextLong(10, 20);
                discontinutiyIntroduced = true;
            }

            if (discontinuityType == DiscontinuityType.IN_EVENTS_THAT_ARE_MIGRATED && fileIndex == 2 * fileCount / 3) {
                // introduce a discontinuity in events that are migrated.
                // Events after the 1/2 way point are migrated, so we will be
                // in the middle of writing migration eligible events.
                origin = random.nextLong(10, 20);
                discontinutiyIntroduced = true;
            }

            final List<PlatformEvent> fileEvents =
                    events.subList(fileIndex * eventsPerFile, (fileIndex + 1) * eventsPerFile);

            final long lowerGenerationBound = fileEvents.getFirst().getGeneration();
            final long upperGenerationBound = fileEvents.getLast().getGeneration();

            final PcesFile file = PcesFile.of(
                    GENERATION_THRESHOLD,
                    startingTime.plusSeconds(fileIndex),
                    fileIndex,
                    lowerGenerationBound,
                    upperGenerationBound,
                    origin,
                    fullPcesPath);
            files.add(file);

            final PcesMutableFile mutableFile = file.getMutableFile();
            for (final PlatformEvent event : fileEvents) {
                mutableFile.writeEvent(event);
                if (discontinutiyIntroduced || discontinuityType == DiscontinuityType.NONE) {
                    postDiscontinuityEvents.add(event);
                }
            }
            mutableFile.close();
        }

        return new PcesFilesWritten(files, postDiscontinuityEvents);
    }

    private enum DiscontinuityType {
        /**
         * Ordinal 0. No discontinuities.
         */
        NONE,
        /**
         * Ordinal 1. Discontinuity int the middle events that are not migrated. None of the migration eligible events
         * should be effected by the discontinuity.
         */
        IN_EVENTS_THAT_ARE_NOT_MIGRATED,
        /**
         * Ordinal 2. Discontinuity in the middle of events that are migration eligible. None of the events that come
         * before the discontinuity should be migrated.
         */
        IN_EVENTS_THAT_ARE_MIGRATED
    }

    /**
     * Validate the basic migration workflow.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void standardMigrationTest(final int discontinuity) throws IOException {
        final Random random = getRandomPrintSeed();
        final DiscontinuityType discontinuityType = DiscontinuityType.values()[discontinuity];

        final Configuration configuration = new TestConfigBuilder()
                .withValue(PcesConfig_.DATABASE_DIRECTORY, pcesPath)
                .getOrCreateConfig();
        LegacyTemporaryFileBuilder.overrideTemporaryFileLocation(temporaryFilePath);

        final FakeTime time = new FakeTime();

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withTime(time)
                .withConfiguration(configuration)
                .withTestFileSystemManagerUnder(testDirectory)
                .withRecycleBin(new RecycleBinImpl(
                        new NoOpMetrics(),
                        AdHocThreadManager.getStaticThreadManager(),
                        time,
                        testDirectory.resolve(recycleBinPath),
                        TestRecycleBin.MAXIMUM_FILE_AGE,
                        TestRecycleBin.MINIMUM_PERIOD))
                .build();

        final PcesFilesWritten filesWritten = generateLegacyPcesFiles(random, discontinuityType);

        // Choose the generation from the middle event as minimum judge generation prior to migration.
        // This will put roughly half of events on either side of the migration boundary.
        final long middleGeneration =
                filesWritten.events.get(filesWritten.events.size() / 2).getGeneration();

        final long migrationRound = random.nextLong(100, 1000);

        PcesBirthRoundMigration.migratePcesToBirthRoundMode(
                platformContext, NodeId.of(0), migrationRound, middleGeneration);

        // We should not find any generation based PCES files in the database directory.
        assertTrue(findPcesFiles(pcesPath, GENERATION_THRESHOLD).isEmpty());

        // We should find exactly one birth round based PCES file in the database directory.
        final List<PcesFile> birthRoundFiles = findPcesFiles(pcesPath, BIRTH_ROUND_THRESHOLD);
        final PcesFile birthRoundFile = birthRoundFiles.getFirst();
        assertEquals(1, birthRoundFiles.size());

        // For every original PCES file, we should find a copy of that file in the recycle bin.
        final List<PcesFile> recycleBinFiles = findPcesFiles(recycleBinPath, GENERATION_THRESHOLD);
        assertEquals(filesWritten.files().size(), recycleBinFiles.size());
        final Set<String> recycleBinFileNames = new HashSet<>();
        for (final PcesFile file : recycleBinFiles) {
            recycleBinFileNames.add(file.getFileName());
        }
        assertEquals(filesWritten.files().size(), recycleBinFileNames.size());
        for (final PcesFile file : filesWritten.files()) {
            assertTrue(recycleBinFileNames.contains(file.getFileName()));
        }

        // Read the events in the new file, make sure we see all events with a generation greater than
        // or equal to the middle generation.
        final List<PlatformEvent> expectedEvents = new ArrayList<>();
        for (final PlatformEvent event : filesWritten.events) {
            if (event.getGeneration() >= middleGeneration) {
                expectedEvents.add(event);
            }
        }
        final IOIterator<PlatformEvent> iterator = new PcesFileIterator(birthRoundFile, 1, BIRTH_ROUND_THRESHOLD);
        final List<PlatformEvent> actualEvents = new ArrayList<>();
        while (iterator.hasNext()) {
            actualEvents.add(iterator.next());
        }
        assertEquals(expectedEvents, actualEvents);

        // Verify that the new file's parameters are valid.
        assertEquals(BIRTH_ROUND_THRESHOLD, birthRoundFile.getFileType());
        assertEquals(time.now(), birthRoundFile.getTimestamp());
        assertEquals(0, birthRoundFile.getSequenceNumber());
        assertEquals(migrationRound, birthRoundFile.getLowerBound());
        assertEquals(migrationRound, birthRoundFile.getUpperBound());
        assertEquals(migrationRound, birthRoundFile.getOrigin());

        // Running migration a second time should have no side effects.
        final Set<Path> allFiles = new HashSet<>();
        try (final Stream<Path> stream = Files.walk(testDirectory)) {
            stream.forEach(allFiles::add);
        }

        PcesBirthRoundMigration.migratePcesToBirthRoundMode(
                platformContext, NodeId.of(0), migrationRound, middleGeneration);

        final Set<Path> allFilesAfterSecondMigration = new HashSet<>();
        try (final Stream<Path> stream = Files.walk(testDirectory)) {
            stream.forEach(allFilesAfterSecondMigration::add);
        }

        assertEquals(allFiles, allFilesAfterSecondMigration);
    }

    @Test
    void genesisWithBirthRoundsTest() throws IOException {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(PcesConfig_.DATABASE_DIRECTORY, pcesPath)
                .getOrCreateConfig();
        LegacyTemporaryFileBuilder.overrideTemporaryFileLocation(temporaryFilePath);

        final FakeTime time = new FakeTime();

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withTime(time)
                .withConfiguration(configuration)
                .withTestFileSystemManagerUnder(testDirectory)
                .withRecycleBin(new RecycleBinImpl(
                        new NoOpMetrics(),
                        AdHocThreadManager.getStaticThreadManager(),
                        time,
                        testDirectory.resolve(recycleBinPath),
                        TestRecycleBin.MAXIMUM_FILE_AGE,
                        TestRecycleBin.MINIMUM_PERIOD))
                .build();

        // should not throw
        PcesBirthRoundMigration.migratePcesToBirthRoundMode(platformContext, NodeId.of(0), ROUND_FIRST, -1);
    }

    @Test
    void botchedMigrationRecoveryTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final Configuration configuration = new TestConfigBuilder()
                .withValue(PcesConfig_.DATABASE_DIRECTORY, pcesPath)
                .getOrCreateConfig();

        final FakeTime time = new FakeTime();

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withTime(time)
                .withConfiguration(configuration)
                .withTestFileSystemManagerUnder(testDirectory)
                .withRecycleBin(new RecycleBinImpl(
                        new NoOpMetrics(),
                        AdHocThreadManager.getStaticThreadManager(),
                        time,
                        testDirectory.resolve(recycleBinPath),
                        TestRecycleBin.MAXIMUM_FILE_AGE,
                        TestRecycleBin.MINIMUM_PERIOD))
                .build();

        final PcesFilesWritten filesWritten = generateLegacyPcesFiles(random, DiscontinuityType.NONE);

        // Choose the generation from the middle event as minimum judge generation prior to migration.
        // This will put roughly half of events on either side of the migration boundary.
        final long middleGeneration =
                filesWritten.events.get(filesWritten.events.size() / 2).getGeneration();

        final long migrationRound = random.nextLong(1, 1000);

        PcesBirthRoundMigration.migratePcesToBirthRoundMode(
                platformContext, NodeId.of(0), migrationRound, middleGeneration);

        // Some funny business: copy the original files back into the PCES database directory.
        // This simulates a crash in the middle of the migration process after we have created
        // the migration file (this is atomic) and before we fully clean up the original files.
        final Path destination = pcesPath.resolve("0");
        try (final Stream<Path> stream = Files.walk(recycleBinPath)) {
            stream.forEach(path -> {
                if (Files.isRegularFile(path)) {
                    try {
                        Files.copy(path, destination.resolve(path.getFileName()));
                    } catch (final IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }

        // Run migration again.
        PcesBirthRoundMigration.migratePcesToBirthRoundMode(
                platformContext, NodeId.of(0), migrationRound, middleGeneration);

        // We should not find any generation based PCES files in the database directory.
        assertTrue(findPcesFiles(pcesPath, GENERATION_THRESHOLD).isEmpty());

        // We should find exactly one birth round based PCES file in the database directory.
        final List<PcesFile> birthRoundFiles = findPcesFiles(pcesPath, BIRTH_ROUND_THRESHOLD);
        final PcesFile birthRoundFile = birthRoundFiles.getFirst();
        assertEquals(1, birthRoundFiles.size());

        // For every original PCES file, we should find a copy of that file in the recycle bin twice:
        // once from the original migration, and a second from the backup made during the botched recovery cleanup.
        final List<PcesFile> recycleBinFiles = findPcesFiles(recycleBinPath, GENERATION_THRESHOLD);
        assertEquals(filesWritten.files().size() * 2, recycleBinFiles.size());
        final Set<String> recycleBinFileNames = new HashSet<>();
        for (final PcesFile file : recycleBinFiles) {
            recycleBinFileNames.add(file.getFileName());
        }
        assertEquals(filesWritten.files().size(), recycleBinFileNames.size());
        for (final PcesFile file : filesWritten.files()) {
            assertTrue(recycleBinFileNames.contains(file.getFileName()));
        }

        // Read the events in the new file, make sure we see all events with a generation greater than
        // or equal to the middle generation.
        final List<PlatformEvent> expectedEvents = new ArrayList<>();
        for (final PlatformEvent event : filesWritten.events) {
            if (event.getGeneration() >= middleGeneration) {
                expectedEvents.add(event);
            }
        }
        final IOIterator<PlatformEvent> iterator = new PcesFileIterator(birthRoundFile, 1, BIRTH_ROUND_THRESHOLD);
        final List<PlatformEvent> actualEvents = new ArrayList<>();
        while (iterator.hasNext()) {
            actualEvents.add(iterator.next());
        }
        assertEquals(expectedEvents, actualEvents);

        // Verify that the new file's parameters are valid.
        assertEquals(BIRTH_ROUND_THRESHOLD, birthRoundFile.getFileType());
        assertEquals(time.now(), birthRoundFile.getTimestamp());
        assertEquals(0, birthRoundFile.getSequenceNumber());
        assertEquals(migrationRound, birthRoundFile.getLowerBound());
        assertEquals(migrationRound, birthRoundFile.getUpperBound());
        assertEquals(migrationRound, birthRoundFile.getOrigin());

        // Running migration a second time should have no side effects.
        final Set<Path> allFiles = new HashSet<>();
        try (final Stream<Path> stream = Files.walk(testDirectory)) {
            stream.forEach(allFiles::add);
        }

        PcesBirthRoundMigration.migratePcesToBirthRoundMode(
                platformContext, NodeId.of(0), migrationRound, middleGeneration);

        final Set<Path> allFilesAfterSecondMigration = new HashSet<>();
        try (final Stream<Path> stream = Files.walk(testDirectory)) {
            stream.forEach(allFilesAfterSecondMigration::add);
        }

        assertEquals(allFiles, allFilesAfterSecondMigration);
    }
}
