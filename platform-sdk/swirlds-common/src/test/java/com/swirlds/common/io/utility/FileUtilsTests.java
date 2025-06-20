// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.utility;

import static com.swirlds.common.io.utility.FileUtils.deleteDirectoryAndLog;
import static com.swirlds.common.io.utility.FileUtils.executeAndRename;
import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.common.io.utility.FileUtils.hardLinkTree;
import static com.swirlds.common.io.utility.FileUtils.throwIfFileExists;
import static com.swirlds.common.io.utility.FileUtils.writeAndFlush;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyDoesNotThrow;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.nio.file.Files.delete;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static org.hiero.base.concurrent.interrupt.Uninterruptable.abortAndThrowIfInterrupted;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("FileUtils Tests")
class FileUtilsTests {

    private static final Configuration CONFIGURATION = ConfigurationBuilder.create()
            .withConfigDataType(TemporaryFileConfig.class)
            .withConfigDataType(StateCommonConfig.class)
            .build();

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    @BeforeEach
    void beforeEach() throws IOException {
        LegacyTemporaryFileBuilder.overrideTemporaryFileLocation(testDirectory.resolve("tmp"));
    }

    @AfterEach
    void cleanup() {
        deleteDirContents(testDirectory.toFile().listFiles());
    }

    /**
     * Recursive delete method to delete all files and directories listed. Java is not able to delete a directory if it
     * has files in it, so recurse into the directory and delete all files there first before deleting the directory.
     */
    private void deleteDirContents(final File[] files) {
        if (files == null) {
            return;
        }
        for (final File file : files) {
            if (file.isDirectory()) {
                deleteDirContents(file.listFiles());
            }
            file.delete();
        }
    }

    @Test
    @DisplayName("absolutePath() From Start Test")
    void absolutePathFromStartTest() throws IOException {
        final File start = new File("start");

        assertEquals(start.getCanonicalFile().toPath(), getAbsolutePath("start"), "invalid path");

        final File expected = new File(start.getCanonicalFile() + "/foo/bar/baz.txt");
        assertEquals(
                expected.toPath(),
                getAbsolutePath("start").resolve("foo").resolve("bar").resolve("baz.txt"),
                "file does not match expected");
    }

    @Test
    @DisplayName("absolutePath() From Current Working Directory Test")
    void absolutePathFromCurrentWorkingDirectoryTest() throws IOException {

        assertEquals(new File(".").getCanonicalFile().toPath(), getAbsolutePath(), "invalid path");

        final File expected = new File(new File(".").getCanonicalFile() + "/foo/bar/baz.txt");
        assertEquals(
                expected.toPath(),
                getAbsolutePath().resolve("foo").resolve("bar").resolve("baz.txt"),
                "invalid path");
    }

    /**
     * Create a directory.
     *
     * @param parent
     * 		the parent directory
     * @param directoryName
     * 		the name of the directory
     * @return a File that points to the directory created
     */
    private static Path createTestDirectory(final Path parent, final String directoryName) throws IOException {
        final Path directory = parent.resolve(directoryName);
        Files.createDirectories(directory);
        assertTrue(exists(directory), "directory should exist");
        assertTrue(isDirectory(directory), "directory is the wrong type of file");

        return directory;
    }

    /**
     * Create a file.
     *
     * @param parent
     * 		the parent directory
     * @param fileName
     * 		the name of the file
     * @param fileContents
     * 		that that is written into the file
     * @return the File created
     */
    private static Path createTestFile(final Path parent, final String fileName, final String fileContents)
            throws IOException {

        final Path file = parent.resolve(fileName);
        final SerializableDataOutputStream out = new SerializableDataOutputStream(new FileOutputStream(file.toFile()));
        out.writeNormalisedString(fileContents);
        out.close();

        assertTrue(exists(file), "data file should exist");
        assertFalse(isDirectory(file), "data should not be a directory");
        return file;
    }

    /**
     * Make sure two files contain the same data
     */
    private static void assertFileEquality(final Path fileA, final Path fileB) {

        try (final FileInputStream inA = new FileInputStream(fileA.toFile());
                final FileInputStream inB = new FileInputStream(fileB.toFile())) {

            final byte[] dataA = inA.readAllBytes();
            final byte[] dataB = inB.readAllBytes();

            assertArrayEquals(dataA, dataB, "files contain different data");

        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Make sure two directory trees are exactly the same. The root directory is permitted to have a different name.
     */
    private static void assertDirectoryTreeEquality(final Path treeA, final Path treeB) throws IOException {
        assertTrue(exists(treeA), "directory " + treeA + " does not exist");
        assertTrue(exists(treeB), "directory " + treeB + " does not exist");

        assertEquals(isDirectory(treeA), isDirectory(treeB), "files should be the same type");

        if (isDirectory(treeA)) {
            final List<Path> childrenA;
            final List<Path> childrenB;
            try (Stream<Path> list = Files.list(treeA)) {
                childrenA = list.toList();
            }
            try (Stream<Path> list = Files.list(treeB)) {
                childrenB = list.toList();
            }
            assertEquals(childrenA.size(), childrenB.size(), "directories have a different number of files¬");

            final Map<String, Path> mapA = new HashMap<>();
            for (final Path path : childrenA) {
                mapA.put(path.getFileName().toString(), path);
            }

            final Map<String, Path> mapB = new HashMap<>();
            for (final Path file : childrenB) {
                assertTrue(mapA.containsKey(file.getFileName().toString()), "file " + file + " is not in both trees");
                mapB.put(file.getFileName().toString(), file);
            }

            for (final String child : mapA.keySet()) {
                assertDirectoryTreeEquality(mapA.get(child), mapB.get(child));
            }
        } else {
            // Files should contain the same data
            assertFileEquality(treeA, treeB);
        }
    }

    @Test
    @DisplayName("Delete Non-Existent Directory Test")
    void deleteNonExistentDirectoryTest() {
        assertDoesNotThrow(
                () -> deleteDirectoryAndLog(new File("foo/bar/baz").toPath()),
                "deleting a non-existent directory should not throw an exception");
    }

    @Test
    @DisplayName("Delete Empty Directory Test")
    void deleteEmptyDirectoryTest() throws IOException {
        final Path dir = createTestDirectory(testDirectory, "emptyDirectory");

        deleteDirectoryAndLog(dir);
        assertFalse(exists(dir), "directory should be deleted");
    }

    @Test
    @DisplayName("Delete Full Directory Test")
    void deleteFullDirectoryTest() throws IOException {
        final Path foo = createTestDirectory(testDirectory, "foo");
        final Path bar = createTestDirectory(foo, "bar");
        final Path data = createTestFile(bar, "data.txt", "this is a test of the emergency testing system");
        final Path baz = createTestDirectory(bar, "baz");

        deleteDirectoryAndLog(foo);
        assertFalse(exists(foo), "directory should be deleted");
        assertFalse(exists(bar), "directory should be deleted");
        assertFalse(exists(baz), "directory should be deleted");
        assertFalse(exists(data), "data file should be deleted");
    }

    @Test
    @DisplayName("hardLinkTree() Empty Directory Test")
    void hardLinkTreeEmptyDirectoryTest() throws IOException {
        final Path foo = createTestDirectory(testDirectory, "foo");
        final Path foo2 = testDirectory.resolve("foo2");

        assertFalse(exists(foo2), "directory should not yet exist");

        hardLinkTree(foo, foo2);

        assertTrue(exists(foo2), "directory should now exist");
        assertDirectoryTreeEquality(foo, foo2);
    }

    @Test
    @DisplayName("hardLinkTree() No Files Test")
    void hardLinkTreeNoFilesTest() throws IOException {
        final Path foo = createTestDirectory(testDirectory, "foo");
        final Path bar = createTestDirectory(foo, "bar");
        createTestDirectory(bar, "baz");

        final Path foo2 = testDirectory.resolve("foo2");

        hardLinkTree(foo, foo2);
        assertTrue(exists(foo2), "directory should now exist");
        assertDirectoryTreeEquality(foo, foo2);
    }

    @Test
    @DisplayName("hardLinkTree() Test")
    void hardLinkTreeTest() throws IOException {
        final Path foo = createTestDirectory(testDirectory, "foo");
        final Path fooData = createTestFile(foo, "fooData.txt", "foo");
        final Path bar = createTestDirectory(foo, "bar");
        final Path barData = createTestFile(bar, "barData.txt", "bar");
        final Path baz = createTestDirectory(bar, "baz");
        final Path bazData = createTestFile(baz, "bazData.txt", "baz");

        final Path foo2 = testDirectory.resolve("foo2");

        hardLinkTree(foo, foo2);
        assertTrue(exists(foo2), "directory should now exist");
        assertDirectoryTreeEquality(foo, foo2);

        // Since the data is hard linked, appending to files should update both trees

        final SerializableDataOutputStream fooOut =
                new SerializableDataOutputStream(new FileOutputStream(fooData.toFile(), true));
        fooOut.writeNormalisedString("FOO");
        fooOut.close();

        final SerializableDataOutputStream barOut =
                new SerializableDataOutputStream(new FileOutputStream(barData.toFile(), true));
        barOut.writeNormalisedString("BAR");
        barOut.close();

        final SerializableDataOutputStream bazOut =
                new SerializableDataOutputStream(new FileOutputStream(bazData.toFile(), true));
        bazOut.writeNormalisedString("BAZ");
        bazOut.close();

        assertDirectoryTreeEquality(foo, foo2);

        // Deleting a file in one should not delete it in the other.
        delete(bazData);
        final Path bazData2 = foo2.resolve("bar").resolve("baz").resolve("bazData.txt");
        assertTrue(exists(bazData2), "file should still exist");

        // Sanity check, utility function should no longer find these trees equal
        assertThrows(
                AssertionError.class,
                () -> assertDirectoryTreeEquality(foo, foo2),
                "directories should not be reported as equal");
    }

    @Test
    @DisplayName("throwIfFileExists() Test")
    void throwIfFileExistsTest() throws IOException {

        // should not crash
        throwIfFileExists();

        // these files do not exist
        throwIfFileExists(new File("foo").toPath());
        throwIfFileExists(new File("foo").toPath());
        throwIfFileExists(new File("foo").toPath(), new File("bar").toPath());
        throwIfFileExists(new File("foo").toPath(), new File("bar").toPath(), new File("baz").toPath());

        final Path foo = createTestDirectory(testDirectory, "foo");
        final Path bar = createTestDirectory(testDirectory, "bar");
        final Path baz = createTestFile(testDirectory, "baz", "blah blah blah");

        // one file exists
        assertThrows(IOException.class, () -> throwIfFileExists(foo), "should have thrown for existing file");
        assertThrows(IOException.class, () -> throwIfFileExists(baz), "should have thrown for existing file");
        assertThrows(
                IOException.class,
                () -> throwIfFileExists(foo, new File("bar").toPath()),
                "should have thrown for existing file");
        assertThrows(
                IOException.class,
                () -> throwIfFileExists(new File("bar").toPath(), bar),
                "should have thrown for existing file");
        assertThrows(
                IOException.class,
                () -> throwIfFileExists(baz, new File("baz").toPath()),
                "should have thrown for existing file");
        assertThrows(
                IOException.class,
                () -> throwIfFileExists(new File("baz").toPath(), baz),
                "should have thrown for existing file");

        // multiple files exist
        assertThrows(IOException.class, () -> throwIfFileExists(foo, bar), "should have thrown for existing file");
        assertThrows(IOException.class, () -> throwIfFileExists(foo, bar, baz), "should have thrown for existing file");
        assertThrows(IOException.class, () -> throwIfFileExists(baz, foo, bar), "should have thrown for existing file");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("executeAndRename() Test")
    void executeAndRenameTest(final boolean createTmpDirectory) throws InterruptedException, IOException {

        final Path foo = testDirectory.resolve("foo");
        final Path data = foo.resolve("data.txt");
        final Path fooTmp = testDirectory.resolve("fooTmp");
        if (createTmpDirectory) {
            // It shouldn't matter if this directory already exists or not
            Files.createDirectories(fooTmp);
        }

        final CountDownLatch startedLatch = new CountDownLatch(1);
        final CountDownLatch pauseLatch = new CountDownLatch(1);

        new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    try {
                        executeAndRename(foo, fooTmp, (final Path directory) -> {
                            final Path dataTmp = directory.resolve("data.txt");

                            writeAndFlush(dataTmp, out -> {
                                out.writeNormalisedString("foo");
                                out.flush();
                                startedLatch.countDown();

                                abortAndThrowIfInterrupted(pauseLatch::await, "interrupted when waiting on latch");

                                out.writeNormalisedString("bar");
                            });
                        });
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .build(true);

        // Wait until writing has started
        startedLatch.await();

        assertFalse(exists(foo), "directory should not yet exist");
        assertTrue(exists(fooTmp.resolve("data.txt")), "temporary file should exist");

        pauseLatch.countDown();

        assertEventuallyDoesNotThrow(
                () -> {
                    assertTrue(exists(foo), "final directory does not exist");
                    assertTrue(exists(data), "final file does not exist");
                    assertFalse(exists(fooTmp), "temporary file should no longer be present");

                    try (final MerkleDataInputStream in =
                            new MerkleDataInputStream(new FileInputStream(data.toFile()))) {
                        assertEquals("foo", in.readNormalisedString(100), "invalid data in file");
                        assertEquals("bar", in.readNormalisedString(100), "invalid data in file");
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                Duration.ofSeconds(1),
                "file should have eventually been written");
    }

    @Test
    @DisplayName("executeAndRename() Crash Test")
    void executeAndRenameCrashTest() throws InterruptedException, IOException {
        final Path foo = testDirectory.resolve("foo");
        final Path fooTmp = testDirectory.resolve("fooTmp");
        Files.createDirectories(fooTmp);

        final CountDownLatch startedLatch = new CountDownLatch(1);
        final CountDownLatch pauseLatch = new CountDownLatch(1);

        new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    try {
                        executeAndRename(foo, fooTmp, (final Path directory) -> {
                            final Path dataTmp = directory.resolve("data.txt");

                            writeAndFlush(dataTmp, out -> {
                                out.writeNormalisedString("foo");
                                out.flush();
                                startedLatch.countDown();

                                abortAndThrowIfInterrupted(pauseLatch::await, "interrupted when waiting on latch");

                                throw new RuntimeException("this is intentionally thrown");
                            });
                        });
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .build(true);

        // Wait until writing has started
        startedLatch.await();

        assertFalse(exists(foo), "directory should not yet exist");
        assertTrue(exists(fooTmp.resolve("data.txt")), "temporary file should exist");

        pauseLatch.countDown();

        assertEventuallyDoesNotThrow(
                () -> {
                    assertFalse(exists(foo), "final should not exist");
                    assertFalse(exists(fooTmp), "temporary file should no longer be present");
                },
                Duration.ofSeconds(1),
                "files should eventually be cleaned up");
    }

    @Test
    @DisplayName("executeAndRename() Auto Generated Tmp Dir Test")
    void executeAndRenameAutoGeneratedTmpDirTest() throws InterruptedException, IOException {

        final Path foo = testDirectory.resolve("foo");
        final Path data = foo.resolve("data.txt");

        final CountDownLatch startedLatch = new CountDownLatch(1);
        final CountDownLatch pauseLatch = new CountDownLatch(1);

        new ThreadConfiguration(getStaticThreadManager())
                .setExceptionHandler((thread, throwable) ->
                        System.out.println("executeAndRenameAutoGeneratedTmpDirTest exception: " + throwable))
                .setRunnable(() -> {
                    try {
                        executeAndRename(
                                foo,
                                (final Path directory) -> {
                                    final Path dataTmp = directory.resolve("data.txt");

                                    writeAndFlush(dataTmp, out -> {
                                        out.writeNormalisedString("foo");
                                        out.flush();
                                        startedLatch.countDown();

                                        abortAndThrowIfInterrupted(
                                                pauseLatch::await, "interrupted when waiting on latch");

                                        out.writeNormalisedString("bar");
                                    });
                                },
                                CONFIGURATION);
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .build(true);

        // Wait until writing has started
        startedLatch.await();

        assertFalse(exists(foo), "directory should not yet exist");

        pauseLatch.countDown();

        assertEventuallyTrue(() -> exists(foo), Duration.ofSeconds(2), "final directory does not exist");
        assertEventuallyTrue(() -> exists(data), Duration.ofSeconds(2), "final file does not exist");

        try (final MerkleDataInputStream in = new MerkleDataInputStream(new FileInputStream(data.toFile()))) {
            assertEquals("foo", in.readNormalisedString(100), "invalid data in file");
            assertEquals("bar", in.readNormalisedString(100), "invalid data in file");
        }
    }

    @Test
    @DisplayName("executeAndRename() Destination Exists Test")
    void executeAndRenameDestinationExistsTest() throws IOException {
        final Path foo = testDirectory.resolve("foo");

        // Creating this directory is expected to cause problems
        Files.createDirectories(foo);

        assertThrows(
                IOException.class,
                () -> executeAndRename(foo, null, CONFIGURATION),
                "existence of directory before hand should cause problems");
    }

    @Test
    @DisplayName("writeAndFlush() Existing File Test")
    void writeAndFlushExistingFileTest() throws IOException {

        final Path foo = testDirectory.resolve("foo");
        Files.createDirectories(foo);

        assertThrows(IOException.class, () -> writeAndFlush(foo, null), "should abort if file does not exist");
    }

    @Test
    @DisplayName("writeAndFlush() Test")
    void writeAndFlushTest() throws IOException {
        final Path foo = testDirectory.resolve("foo");
        throwIfFileExists(foo);

        writeAndFlush(foo, out -> out.writeNormalisedString("foobarbaz"));

        assertTrue(exists(foo), "file should exist");

        try (final MerkleDataInputStream in = new MerkleDataInputStream(new FileInputStream(foo.toFile()))) {
            assertEquals("foobarbaz", in.readNormalisedString(1000), "invalid data in file");
        }
    }

    @Test
    @DisplayName("findFiles() Test")
    void findFilesTest() throws IOException {
        final Path dir = testDirectory.resolve("findfiles");
        final Path subdir = dir.resolve("subdir");
        Files.createDirectories(subdir);
        final Path first = dir.resolve("first.foo");
        final Path second = dir.resolve("second.bar");
        final Path third = subdir.resolve("third.foo");
        Files.createFile(first);
        Files.createFile(second);
        Files.createFile(third);

        final List<Path> files = FileUtils.findFiles(dir, ".foo");
        assertEquals(2, files.size(), "incorrect number of files found");
        assertTrue(files.contains(first), "first.foo not found");
        assertTrue(files.contains(third), "third.foo not found");
    }
}
