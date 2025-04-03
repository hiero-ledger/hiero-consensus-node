// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.test.fixtures.io.FileManipulation.writeRandomBytes;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.event.preconsensus.PcesFile.EVENT_FILE_SEPARATOR;
import static com.swirlds.platform.event.preconsensus.PcesFile.MAXIMUM_BIRTH_ROUND_PREFIX;
import static com.swirlds.platform.event.preconsensus.PcesFile.MAXIMUM_GENERATION_PREFIX;
import static com.swirlds.platform.event.preconsensus.PcesFile.MINIMUM_BIRTH_ROUND_PREFIX;
import static com.swirlds.platform.event.preconsensus.PcesFile.MINIMUM_GENERATION_PREFIX;
import static com.swirlds.platform.event.preconsensus.PcesFile.ORIGIN_PREFIX;
import static com.swirlds.platform.event.preconsensus.PcesFile.SEQUENCE_NUMBER_PREFIX;
import static org.hiero.consensus.model.event.AncientMode.BIRTH_ROUND_THRESHOLD;
import static org.hiero.consensus.model.event.AncientMode.GENERATION_THRESHOLD;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.randomInstant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.time.Time;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.io.utility.RecycleBinImpl;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.test.fixtures.TestRecycleBin;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.utility.test.fixtures.RandomUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("PcesFile Tests")
class PcesFileTests {

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    @BeforeEach
    void beforeEach() throws IOException {
        FileUtils.deleteDirectory(testDirectory);
        Files.createDirectories(testDirectory);
    }

    @AfterEach
    void afterEach() throws IOException {
        FileUtils.deleteDirectory(testDirectory);
    }

    protected static Stream<Arguments> buildArguments() {
        return Stream.of(Arguments.of(GENERATION_THRESHOLD), Arguments.of(BIRTH_ROUND_THRESHOLD));
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Invalid Parameters Test")
    void invalidParametersTest(@NonNull final AncientMode ancientMode) {
        assertThrows(
                IllegalArgumentException.class,
                () -> PcesFile.of(ancientMode, Instant.now(), -1, 1, 2, 0, Path.of("foo")));

        assertThrows(
                IllegalArgumentException.class,
                () -> PcesFile.of(ancientMode, Instant.now(), 1, -1, 2, 0, Path.of("foo")));

        assertThrows(
                IllegalArgumentException.class,
                () -> PcesFile.of(ancientMode, Instant.now(), 1, -2, -1, 0, Path.of("foo")));

        assertThrows(
                IllegalArgumentException.class,
                () -> PcesFile.of(ancientMode, Instant.now(), 1, 1, -1, 0, Path.of("foo")));

        assertThrows(
                IllegalArgumentException.class,
                () -> PcesFile.of(ancientMode, Instant.now(), 1, 2, 1, 0, Path.of("foo")));

        assertThrows(NullPointerException.class, () -> PcesFile.of(ancientMode, null, 1, 1, 2, 0, Path.of("foo")));

        assertThrows(
                IllegalArgumentException.class,
                () -> PcesFile.of(ancientMode, Instant.now(), 1, 1, 2, -1, Path.of("foo")));

        assertThrows(NullPointerException.class, () -> PcesFile.of(ancientMode, Instant.now(), 1, 1, 2, 0, null));
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("File Name Test")
    void fileNameTest(@NonNull final AncientMode ancientMode) {
        final Random random = getRandomPrintSeed();

        int count = 100;
        while (count-- > 0) {
            final long sequenceNumber = random.nextLong(1000);
            final long lowerBound = random.nextLong(1000);
            final long upperBound = random.nextLong(lowerBound, lowerBound + 1000);
            final long origin = random.nextLong(1000);
            final Instant timestamp = RandomUtils.randomInstant(random);

            final String lowerBoundPrefix =
                    ancientMode == GENERATION_THRESHOLD ? MINIMUM_GENERATION_PREFIX : MINIMUM_BIRTH_ROUND_PREFIX;
            final String upperBoundPrefix =
                    ancientMode == GENERATION_THRESHOLD ? MAXIMUM_GENERATION_PREFIX : MAXIMUM_BIRTH_ROUND_PREFIX;

            final String expectedName =
                    timestamp.toString().replace(":", "+") + EVENT_FILE_SEPARATOR + SEQUENCE_NUMBER_PREFIX
                            + sequenceNumber + EVENT_FILE_SEPARATOR + lowerBoundPrefix
                            + lowerBound + EVENT_FILE_SEPARATOR + upperBoundPrefix
                            + upperBound + EVENT_FILE_SEPARATOR + ORIGIN_PREFIX + origin + ".pces";

            final PcesFile file = PcesFile.of(
                    ancientMode, timestamp, sequenceNumber, lowerBound, upperBound, origin, Path.of("foo/bar"));

            assertEquals(expectedName, file.getFileName());
            assertEquals(expectedName, file.toString());
        }
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("File Path Test")
    void filePathTest(@NonNull final AncientMode ancientMode) {
        final Random random = getRandomPrintSeed();

        int count = 100;
        while (count-- > 0) {

            final long sequenceNumber = random.nextLong(1000);
            final long lowerBound = random.nextLong(1000);
            final long upperBound = random.nextLong(lowerBound, lowerBound + 1000);
            final long origin = random.nextLong(1000);
            final Instant timestamp = RandomUtils.randomInstant(random);

            final ZonedDateTime zonedDateTime = timestamp.atZone(ZoneId.systemDefault());
            final int year = zonedDateTime.getYear();
            final int month = zonedDateTime.getMonthValue();
            final int day = zonedDateTime.getDayOfMonth();

            final Path expectedPath = Path.of(
                    "foo/bar", String.format("%04d", year), String.format("%02d", month), String.format("%02d", day));

            assertEquals(
                    expectedPath,
                    PcesFile.of(
                                    ancientMode,
                                    timestamp,
                                    sequenceNumber,
                                    lowerBound,
                                    upperBound,
                                    origin,
                                    Path.of("foo/bar"))
                            .getPath()
                            .getParent());
        }
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Parsing Test")
    void parsingTest(@NonNull final AncientMode ancientMode) throws IOException {
        final Random random = getRandomPrintSeed();

        int count = 100;
        while (count-- > 0) {
            final long sequenceNumber = random.nextLong(1000);
            final long lowerBound = random.nextLong(1000);
            final long upperBound = random.nextLong(lowerBound, lowerBound + 1000);
            final long origin = random.nextLong(1000);
            final Instant timestamp = RandomUtils.randomInstant(random);

            final Path directory = Path.of("foo/bar/baz");

            final PcesFile expected =
                    PcesFile.of(ancientMode, timestamp, sequenceNumber, lowerBound, upperBound, origin, directory);

            final PcesFile parsed = PcesFile.of(expected.getPath());

            assertEquals(expected, parsed);
            assertEquals(sequenceNumber, parsed.getSequenceNumber());
            assertEquals(lowerBound, parsed.getLowerBound());
            assertEquals(upperBound, parsed.getUpperBound());
            assertEquals(origin, parsed.getOrigin());
            assertEquals(timestamp, parsed.getTimestamp());
            assertEquals(ancientMode, parsed.getFileType());
        }
    }

    @Test
    @DisplayName("Invalid File Parsing Test")
    void invalidFileParsingTest() {
        // Invalid format
        assertThrows(IOException.class, () -> PcesFile.of(Path.of(";laksjdf;laksjdf")));
        assertThrows(IOException.class, () -> PcesFile.of(Path.of(".pces")));
        assertThrows(IOException.class, () -> PcesFile.of(Path.of("foobar.txt")));
        assertThrows(IOException.class, () -> PcesFile.of(Path.of("foobar.pces")));
        assertThrows(IOException.class, () -> PcesFile.of(Path.of("foobar.pcesD")));
        assertThrows(
                IOException.class, () -> PcesFile.of(Path.of("1997-0DERPT21+42+49.730Z_seq443_ming303_maxg884.pces")));
        assertThrows(
                IOException.class,
                () -> PcesFile.of(Path.of("1997-02-16T21+42+49.730Z_seq4DERP3_ming303_maxg884.pces")));
        assertThrows(
                IOException.class,
                () -> PcesFile.of(Path.of("1997-02-16T21+42+49.730Z_seq443_ming303_maxg8DERP4.pces")));

        // Valid format, invalid data
        assertThrows(
                IOException.class, () -> PcesFile.of(Path.of("1997-02-16T21+42+49.730Z_seq443_ming884_maxg303.pces")));
    }

    @SuppressWarnings("resource")
    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Deletion Test")
    void deletionTest(@NonNull final AncientMode ancientMode) throws IOException {
        final Random random = getRandomPrintSeed();
        final Instant now = Instant.now();

        // When we start out, the test directory should be empty.
        int filesCount;
        try (Stream<Path> list = Files.list(testDirectory)) {
            filesCount = (int) list.count();
        }
        assertEquals(0, filesCount, "Unexpected number of files: " + filesCount);

        final List<Instant> times = new ArrayList<>();
        times.add(now);
        times.add(now.plus(Duration.ofMinutes(1)));
        times.add(now.plus(Duration.ofMinutes(2)));
        times.add(now.plus(Duration.ofMinutes(3)));
        times.add(now.plus(Duration.ofMinutes(4)));
        times.add(now.plus(Duration.ofMinutes(5)));
        times.add(now.plus(Duration.ofHours(1)));
        times.add(now.plus(Duration.ofDays(1)));
        times.add(now.plus(Duration.ofDays(40)));
        times.add(now.plus(Duration.ofDays(400)));

        final List<PcesFile> files = new ArrayList<>();
        for (int index = 0; index < times.size(); index++) {
            final Instant timestamp = times.get(index);
            // We don't care about ancient indicators for this test
            final PcesFile file = PcesFile.of(ancientMode, timestamp, index, 0, 0, 0, testDirectory);

            writeRandomBytes(random, file.getPath(), 100);
            files.add(file);
        }

        // Delete the files in a random order.
        Collections.shuffle(files, random);
        final Set<PcesFile> deletedFiles = new HashSet<>();
        for (final PcesFile file : files) {

            for (final PcesFile fileToCheck : files) {
                if (deletedFiles.contains(fileToCheck)) {
                    assertFalse(Files.exists(fileToCheck.getPath()));
                } else {
                    assertTrue(Files.exists(fileToCheck.getPath()));
                }
            }

            file.deleteFile(testDirectory);

            if (random.nextBoolean()) {
                // Deleting twice shouldn't have any ill effects
                file.deleteFile(testDirectory);
            }

            deletedFiles.add(file);
        }

        // After all files have been deleted, the test directory should be empty again.
        try (Stream<Path> list = Files.list(testDirectory)) {
            filesCount = (int) list.count();
        }
        assertEquals(0, filesCount, "Unexpected number of files: " + filesCount);
    }

    @SuppressWarnings("resource")
    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Recycle Test")
    void recycleTest(@NonNull final AncientMode ancientMode) throws IOException {
        final Random random = getRandomPrintSeed();
        final Instant now = Instant.now();

        final Path streamDirectory = testDirectory.resolve("data");
        final Path recycleDirectory = testDirectory.resolve("recycle");

        RecycleBin bin = new RecycleBinImpl(
                new NoOpMetrics(),
                getStaticThreadManager(),
                Time.getCurrent(),
                recycleDirectory,
                TestRecycleBin.MAXIMUM_FILE_AGE,
                TestRecycleBin.MINIMUM_PERIOD);

        Files.createDirectories(streamDirectory);
        Files.createDirectories(recycleDirectory);

        // When we start out, the test directory should be empty.
        int filesCount;
        try (Stream<Path> list = Files.list(streamDirectory)) {
            filesCount = (int) list.count();
        }
        assertEquals(0, filesCount, "Unexpected number of files: " + filesCount);

        final List<Instant> times = new ArrayList<>();
        times.add(now);
        times.add(now.plus(Duration.ofMinutes(1)));
        times.add(now.plus(Duration.ofMinutes(2)));
        times.add(now.plus(Duration.ofMinutes(3)));
        times.add(now.plus(Duration.ofMinutes(4)));
        times.add(now.plus(Duration.ofMinutes(5)));
        times.add(now.plus(Duration.ofHours(1)));
        times.add(now.plus(Duration.ofDays(1)));
        times.add(now.plus(Duration.ofDays(40)));
        times.add(now.plus(Duration.ofDays(400)));

        final List<PcesFile> files = new ArrayList<>();
        for (int index = 0; index < times.size(); index++) {
            final Instant timestamp = times.get(index);
            // We don't care about ancient indicators for this test
            final PcesFile file = PcesFile.of(ancientMode, timestamp, index, 0, 0, 0, streamDirectory);

            writeRandomBytes(random, file.getPath(), 100);
            files.add(file);
        }

        // Delete the files in a random order.
        Collections.shuffle(files, random);
        final Set<PcesFile> deletedFiles = new HashSet<>();
        for (final PcesFile file : files) {

            for (final PcesFile fileToCheck : files) {
                if (deletedFiles.contains(fileToCheck)) {
                    assertFalse(Files.exists(fileToCheck.getPath()));
                } else {
                    assertTrue(Files.exists(fileToCheck.getPath()));
                }
            }

            file.deleteFile(streamDirectory, bin);

            if (random.nextBoolean()) {
                // Deleting twice shouldn't have any ill effects
                file.deleteFile(streamDirectory, bin);
            }

            deletedFiles.add(file);
        }

        // After all files have been deleted, the test directory should be empty again.
        try (Stream<Path> list = Files.list(streamDirectory)) {
            filesCount = (int) list.count();
        }
        assertEquals(0, filesCount, "Unexpected number of files: " + filesCount);

        // All files should have been moved to the recycle directory
        for (final PcesFile file : files) {
            assertTrue(Files.exists(recycleDirectory.resolve(file.getPath().getFileName())));
        }
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("compareTo() Test")
    void compareToTest(@NonNull final AncientMode ancientMode) {
        final Random random = getRandomPrintSeed();

        final Path directory = Path.of("foo/bar/baz");

        for (int i = 0; i < 1000; i++) {
            final long sequenceA = random.nextLong(100);
            final long sequenceB = random.nextLong(100);

            final long lowerBoundA = random.nextLong(100);
            final long lowerBoundB = random.nextLong(100);

            final long upperBoundA = random.nextLong(lowerBoundA, lowerBoundA + 100);
            final long upperBoundB = random.nextLong(lowerBoundB, lowerBoundB + 100);

            final PcesFile a = PcesFile.of(
                    ancientMode,
                    randomInstant(random),
                    sequenceA,
                    lowerBoundA,
                    upperBoundA,
                    random.nextLong(1000),
                    directory);
            final PcesFile b = PcesFile.of(
                    ancientMode,
                    randomInstant(random),
                    sequenceB,
                    lowerBoundB,
                    upperBoundB,
                    random.nextLong(1000),
                    directory);

            assertEquals(Long.compare(sequenceA, sequenceB), a.compareTo(b));
        }
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("canContain() Test")
    void canContainTest(@NonNull final AncientMode ancientMode) {
        final Random random = getRandomPrintSeed();

        final Path directory = Path.of("foo/bar/baz");

        for (int i = 0; i < 1000; i++) {
            final long sequenceNumber = random.nextLong(1000);
            final long lowerBound = random.nextLong(1000);
            final long upperBound = random.nextLong(lowerBound + 1, lowerBound + 1000);
            final Instant timestamp = RandomUtils.randomInstant(random);

            final PcesFile file =
                    PcesFile.of(ancientMode, timestamp, sequenceNumber, lowerBound, upperBound, 0, directory);

            // An event with a sequence number that is too small
            assertFalse(file.canContain(lowerBound - random.nextLong(1, 100)));

            // An event with a sequence number matching the minimum exactly
            assertTrue(file.canContain(lowerBound));

            // An event with a sequence somewhere between the minimum and maximum
            assertTrue(file.canContain(upperBound));

            // An event with a sequence somewhere exactly matching the maximum
            assertTrue(file.canContain(upperBound));

            // An event with a sequence number that is too big
            assertFalse(file.canContain(upperBound + random.nextLong(1, 100)));
        }
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Span Compression Test")
    void spanCompressionTest(@NonNull final AncientMode ancientMode) {
        final Random random = getRandomPrintSeed();

        final Path directory = Path.of("foo/bar/baz");

        final long sequenceNumber = random.nextLong(1000);
        final long lowerBound = random.nextLong(1000);
        final long upperBound = random.nextLong(lowerBound + 5, lowerBound + 1000);
        final long origin = random.nextLong(1000);
        final Instant timestamp = randomInstant(random);

        final PcesFile file =
                PcesFile.of(ancientMode, timestamp, sequenceNumber, lowerBound, upperBound, origin, directory);

        assertThrows(IllegalArgumentException.class, () -> file.buildFileWithCompressedSpan(lowerBound - 1));
        assertThrows(IllegalArgumentException.class, () -> file.buildFileWithCompressedSpan(upperBound + 1));

        final long newMaximumUpperBound = random.nextLong(lowerBound, upperBound);

        final PcesFile compressedFile = file.buildFileWithCompressedSpan(newMaximumUpperBound);

        assertEquals(sequenceNumber, compressedFile.getSequenceNumber());
        assertEquals(lowerBound, compressedFile.getLowerBound());
        assertEquals(newMaximumUpperBound, compressedFile.getUpperBound());
        assertEquals(origin, compressedFile.getOrigin());
        assertEquals(timestamp, compressedFile.getTimestamp());
    }
}
