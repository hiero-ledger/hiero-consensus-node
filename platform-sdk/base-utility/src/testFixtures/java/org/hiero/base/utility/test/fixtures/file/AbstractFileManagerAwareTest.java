package org.hiero.base.utility.test.fixtures.file;

import org.hiero.base.file.FileSystemManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Base class for tests that want to use {@link FileSystemManager}.
 * {@link #fileSystemManager} uses {@link TempDir} created by JUnit.
 */
public abstract class AbstractFileManagerAwareTest {

    protected static FileSystemManager fileSystemManager;

    @BeforeAll
    static void setupFileSystemManager(@TempDir Path fsmTempDir) {
        fileSystemManager = new FileSystemManager(fsmTempDir);
    }
}
