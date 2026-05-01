// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.filesystem.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.base.test.fixtures.concurrent.TestExecutor;
import com.swirlds.base.test.fixtures.concurrent.WithTestExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.base.file.FileSystemManager;
import org.hiero.base.file.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@WithTestExecutor
public class FileSystemManagerTest {

    // These values are the default values defined in FileSystemConfig, they are used here to verify
    // that the FileSystemManager is using the correct default values when creating directories.
    private static final String EXPECTED_ROOT_DIR_NAME = "data/saved";
    private static final String EXPECTED_TMP_DIR_NAME = "swirlds-tmp";

    @TempDir
    private Path tempDir;

    private Path testRootDir;

    @BeforeEach
    public void setup() {
        testRootDir = tempDir.resolve("FileSystemManagerImplTest").resolve(EXPECTED_ROOT_DIR_NAME);
    }

    public FileSystemManager getFileSystemManager() {
        return new FileSystemManager(testRootDir, EXPECTED_TMP_DIR_NAME);
    }

    @Test
    public void testNew_rootPathIsCreated() {
        // given
        getFileSystemManager();
        // then
        assertThat(testRootDir).isDirectory().startsWithRaw(tempDir).isNotEmptyDirectory();
    }

    @Test
    public void testNew_aLargeRootPathIsCreated() {
        // given
        final String largeRootLocation =
                new StringBuffer(testRootDir.toString()).repeat("/child", 100).toString();
        new FileSystemManager(largeRootLocation, EXPECTED_TMP_DIR_NAME);
        // then
        assertThat(Path.of(largeRootLocation)).isDirectory().isNotEmptyDirectory();
    }

    @Test
    public void testNew_deletesAllInTempFolderIfPathExist() throws IOException {
        // given
        final Path tmpDir = testRootDir.resolve("tmp");
        Files.createDirectories(tmpDir);
        final List<String> tmpFileNames = IntStream.range(0, 10)
                .boxed()
                .map(x -> FileUtils.rethrowIO(() -> Files.createTempFile(tmpDir, x + "", null)))
                .map(p -> p.toFile().getName())
                .toList();
        // when
        getFileSystemManager();

        // then
        assertThat(testRootDir)
                .isNotEmptyDirectory()
                .isDirectoryContaining("glob:**" + EXPECTED_TMP_DIR_NAME)
                .isDirectoryContaining("glob:**" + EXPECTED_TMP_DIR_NAME);
        assertThat(tmpDir).isDirectoryNotContaining("glob:{" + String.join(",", tmpFileNames) + "}");
    }

    @Test
    public void testResolve_validRelativePath() {
        // given
        final FileSystemManager fileSystemManager = getFileSystemManager();
        final Path relativePath = Paths.get("file.txt");
        final Path resolvedPath = fileSystemManager.resolve(relativePath);

        // then
        // Assert that the resolved path is correctly formed from root and relative path
        assertThat(resolvedPath).isEqualTo(testRootDir.resolve("file.txt"));
    }

    @Test
    public void testResolve_emptyPath() {
        // given
        final FileSystemManager fileSystemManager = getFileSystemManager();
        final Path relativeEmptyPath = Paths.get("");

        // then
        // Assert that empty path should not resolve to the root path
        assertThrows(
                IllegalArgumentException.class,
                () -> fileSystemManager.resolve(relativeEmptyPath),
                () -> "Requested path is cannot be converted to valid relative path inside of:" + testRootDir);
    }

    @Test
    public void testResolve_absolutePath() {
        // given
        final FileSystemManager fileSystemManager = getFileSystemManager();
        final Path absolutePath = Paths.get("/home/user/file.txt");

        // then
        // Assert that absolute paths are not allowed
        assertThrows(
                IllegalArgumentException.class,
                () -> fileSystemManager.resolve(absolutePath),
                () -> "Requested path is cannot be converted to valid relative path inside of:" + testRootDir);
    }

    @Test
    public void testResolve_pathEscapingRoot() {
        // given
        final FileSystemManager fileSystemManager = getFileSystemManager();
        final Path escapingPath = Paths.get("../etc/passwd");

        // then
        // Assert that paths trying to escape the root are not allowed
        assertThrows(
                IllegalArgumentException.class,
                () -> fileSystemManager.resolve(escapingPath),
                () -> "Requested path is cannot be converted to valid relative path inside of:" + testRootDir);
    }

    @Test
    public void testResolveNewTemp_validName() {
        // given
        final FileSystemManager fileSystemManager = getFileSystemManager();
        final String name = "myTempFile";
        final Path tempPath = fileSystemManager.resolveNewTemp(name);

        // then
        // Assert that the temporary path has the expected format
        assertThat(tempPath)
                .doesNotExist()
                .satisfies(p -> assertThat(p.toAbsolutePath().toString())
                        .contains(testRootDir.resolve(EXPECTED_TMP_DIR_NAME).toString()))
                .satisfies(p -> assertThat(p.getFileName().toString()).endsWith("myTempFile"));
    }

    @Test
    public void testResolveNewTemp_concurrentCallDoesNotThrow(TestExecutor testExecutor) {
        // given
        final FileSystemManager fileSystemManager = getFileSystemManager();
        Runnable r = () -> fileSystemManager.resolveNewTemp("aTag");
        Runnable[] params = Stream.generate(() -> r).limit(100).toArray(Runnable[]::new);
        // when
        final Executable executable = () -> testExecutor.executeAndWait(params);
        // then
        assertDoesNotThrow(executable);
    }
}
