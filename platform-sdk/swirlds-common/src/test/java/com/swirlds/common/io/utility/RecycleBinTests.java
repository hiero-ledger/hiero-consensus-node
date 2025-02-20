// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.utility;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyDoesNotThrow;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("RecycleBin Tests")
class RecycleBinTests {

    public static final Duration MAXIMUM_FILE_AGE = Duration.of(7, ChronoUnit.DAYS);
    public static final Duration MINIMUM_PERIOD = Duration.of(1, ChronoUnit.DAYS);
    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    private Path recycleBinDirectory;

    @TempDir
    private Path workDirectory;
    /**
     * Create a file and write a string to it.
     */
    private void writeFile(@NonNull final Path path, @NonNull final String contents) throws IOException {
        final Path parent = path.getParent();
        Files.createDirectories(parent);

        final BufferedWriter writer = Files.newBufferedWriter(path);
        writer.write(contents);
        writer.close();
    }

    /**
     * Validate that a file exists and contains the expected contents.
     */
    private void validateFile(@NonNull final Path path, @NonNull final String expectedContents) throws IOException {
        assertTrue(Files.exists(path));
        final String contents = Files.readString(path);
        assertTrue(contents.equals(expectedContents));
    }

    @Test
    @DisplayName("Recycle File Test")
    void recycleFileTest() throws IOException {
        final RecycleBin recycleBin = createRecycleBin(Time.getCurrent(), MINIMUM_PERIOD);

        final Path path1 = workDirectory.resolve("file1.txt");
        writeFile(path1, "file1");

        final Path path2 = workDirectory.resolve("file2.txt");
        writeFile(path2, "file2");

        final Path path3 = workDirectory.resolve("file3.txt");
        writeFile(path3, "file3");

        recycleBin.recycle(path1);
        recycleBin.recycle(path2);
        recycleBin.recycle(path3);

        assertFalse(Files.exists(path1));
        final Path recycledPath1 = recycleBinDirectory.resolve("file1.txt");
        validateFile(recycledPath1, "file1");

        assertFalse(Files.exists(path2));
        final Path recycledPath2 = recycleBinDirectory.resolve("file2.txt");
        validateFile(recycledPath2, "file2");

        assertFalse(Files.exists(path3));
        final Path recycledPath3 = recycleBinDirectory.resolve("file3.txt");
        validateFile(recycledPath3, "file3");
    }

    @Test
    @DisplayName("Recycle Directory Test")
    void recycleDirectoryTest() throws IOException {
        final RecycleBin recycleBin = createRecycleBin(Time.getCurrent(), MINIMUM_PERIOD);

        final Path directory = workDirectory.resolve("foo/bar/baz");
        Files.createDirectories(directory);

        final Path path1 = directory.resolve("file1.txt");
        writeFile(path1, "file1");

        final Path path2 = directory.resolve("file2.txt");
        writeFile(path2, "file2");

        final Path path3 = directory.resolve("file3.txt");
        writeFile(path3, "file3");

        recycleBin.recycle(workDirectory.resolve("foo"));

        assertFalse(Files.exists(path1));
        final Path recycledPath1 = recycleBinDirectory.resolve("foo/bar/baz").resolve("file1.txt");
        validateFile(recycledPath1, "file1");

        assertFalse(Files.exists(path2));
        final Path recycledPath2 = recycleBinDirectory.resolve("foo/bar/baz").resolve("file2.txt");
        validateFile(recycledPath2, "file2");

        assertFalse(Files.exists(path3));
        final Path recycledPath3 = recycleBinDirectory.resolve("foo/bar/baz").resolve("file3.txt");
        validateFile(recycledPath3, "file3");
    }

    @Test
    @DisplayName("Recycle Non-Existent File Test")
    void recycleNonExistentFileTest() throws IOException {
        final RecycleBin recycleBin = createRecycleBin(Time.getCurrent(), MINIMUM_PERIOD);

        final Path path = recycleBinDirectory.resolve("file.txt");
        recycleBin.recycle(path);

        final Path recycledPath =
                recycleBinDirectory.resolve("swirlds-recycle-bin").resolve("0").resolve("file.txt");
        assertFalse(Files.exists(recycledPath));
    }

    @Test
    @DisplayName("Recycle Duplicate File Test")
    void recycleDuplicateFileTest() throws IOException {
        final RecycleBin recycleBin = createRecycleBin(Time.getCurrent(), MINIMUM_PERIOD);

        final Path path = workDirectory.resolve("file.txt");
        final Path recycledPath = recycleBinDirectory.resolve("file.txt");

        writeFile(path, "foo");
        recycleBin.recycle(path);
        assertFalse(Files.exists(path));
        validateFile(recycledPath, "foo");

        writeFile(path, "bar");
        recycleBin.recycle(path);
        assertFalse(Files.exists(path));
        validateFile(recycledPath, "bar");

        writeFile(path, "baz");
        recycleBin.recycle(path);
        assertFalse(Files.exists(path));
        validateFile(recycledPath, "baz");
    }

    @Test
    @DisplayName("Files Deleted After Time Passes")
    void filesDeletedAfterTimePasses() throws IOException, InterruptedException {
        final FakeTime time = new FakeTime(Instant.now(), Duration.ZERO);
        final RecycleBinImpl recycleBin = createRecycleBin(time, Duration.of(1, ChronoUnit.NANOS));
        recycleBin.start();

        // Recycle some files.
        final Path path1 = workDirectory.resolve("file1.txt");
        writeFile(path1, "file1");

        final Path path2 = workDirectory.resolve("file2.txt");
        writeFile(path2, "file2");

        final Path path3 = workDirectory.resolve("foo/bar/baz/file3.txt");
        writeFile(path3, "file3");

        recycleBin.recycle(path1);
        recycleBin.recycle(path2);
        recycleBin.recycle(workDirectory.resolve("foo"));

        assertFalse(Files.exists(path1));
        assertFalse(Files.exists(path2));
        assertFalse(Files.exists(path3));

        final Path recycledPath1 = recycleBinDirectory.resolve("file1.txt");
        final Path recycledPath2 = recycleBinDirectory.resolve("file2.txt");
        final Path recycledPath3 = recycleBinDirectory.resolve("foo/bar/baz/file3.txt");

        // Wait some time. Although the recycle bin will have had time to delete files if it wanted to,
        // it won't have actually deleted them yet because not enough time has passed.
        for (int i = 0; i < 100; i++) {
            time.tick(Duration.ofMinutes(1));
            MILLISECONDS.sleep(5);

            assertTrue(Files.exists(recycledPath1));
            assertTrue(Files.exists(recycledPath2));
            assertTrue(Files.exists(recycledPath3));
        }

        // Advance time by the maximum file age. Now the recycle bin will be able to delete the files.
        time.tick(MAXIMUM_FILE_AGE);

        assertEventuallyDoesNotThrow(
                () -> {
                    assertFalse(Files.exists(recycledPath1));
                    assertFalse(Files.exists(recycledPath2));
                    assertFalse(Files.exists(recycledPath3));
                },
                Duration.ofSeconds(1),
                "Files were not deleted after the maximum file age elapsed.");

        recycleBin.stop();
    }

    @Test
    @DisplayName("clear() Test")
    void clearTest() throws IOException, InterruptedException {
        final FakeTime time = new FakeTime(Instant.now(), Duration.ZERO);

        final RecycleBinImpl recycleBin = createRecycleBin(time, Duration.of(1, ChronoUnit.NANOS));
        recycleBin.start();

        // Recycle some files.
        final Path path1 = workDirectory.resolve("file1.txt");
        writeFile(path1, "file1");

        final Path path2 = workDirectory.resolve("file2.txt");
        writeFile(path2, "file2");

        final Path path3 = workDirectory.resolve("foo/bar/baz/file3.txt");
        writeFile(path3, "file3");

        recycleBin.recycle(path1);
        recycleBin.recycle(path2);
        recycleBin.recycle(workDirectory.resolve("foo"));

        assertFalse(Files.exists(path1));
        assertFalse(Files.exists(path2));
        assertFalse(Files.exists(path3));

        final Path recycledPath1 = recycleBinDirectory.resolve("file1.txt");
        final Path recycledPath2 = recycleBinDirectory.resolve("file2.txt");
        final Path recycledPath3 = recycleBinDirectory.resolve("foo/bar/baz/file3.txt");

        // Wait some time. Although the recycle bin will have had time to delete files if it wanted to,
        // it won't have actually deleted them yet because not enough time has passed.
        for (int i = 0; i < 100; i++) {
            time.tick(Duration.ofMinutes(1));
            MILLISECONDS.sleep(5);

            assertTrue(Files.exists(recycledPath1));
            assertTrue(Files.exists(recycledPath2));
            assertTrue(Files.exists(recycledPath3));
        }

        // Manually clear the recycle bin
        recycleBin.clear();

        assertEventuallyDoesNotThrow(
                () -> {
                    assertFalse(Files.exists(recycledPath1));
                    assertFalse(Files.exists(recycledPath2));
                    assertFalse(Files.exists(recycledPath3));
                },
                Duration.ofSeconds(1),
                "Files were not deleted after the maximum file age elapsed.");

        recycleBin.stop();
    }

    private RecycleBinImpl createRecycleBin(final Time time, final Duration minimumPeriod) {
        return new RecycleBinImpl(
                new NoOpMetrics(),
                getStaticThreadManager(),
                time,
                recycleBinDirectory,
                RecycleBinTests.MAXIMUM_FILE_AGE,
                minimumPeriod);
    }
}
