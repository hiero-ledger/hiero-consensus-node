// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures;

import static org.hiero.base.file.FileUtils.rethrowIO;

import com.swirlds.common.io.filesystem.FileSystemManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import org.hiero.base.file.FileUtils;

/**
 * A {@link FileSystemManager} that cleans up the created files and directories on JVM shutdown. It is useful for
 * testing purposes to avoid leaving temporary files on the file system after the tests are done.
 */
public class TestFileSystemManager extends FileSystemManager {

    private static final String TMP = "tmp";

    public TestFileSystemManager(@NonNull final Path rootLocation) {
        super(rootLocation, TMP);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> rethrowIO(() -> FileUtils.deleteDirectory(rootPath))));
    }
}
