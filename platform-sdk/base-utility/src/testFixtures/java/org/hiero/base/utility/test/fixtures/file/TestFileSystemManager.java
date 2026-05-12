// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility.test.fixtures.file;

import static org.hiero.base.file.FileUtils.rethrowIO;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import org.hiero.base.file.FileSystemManager;
import org.hiero.base.file.FileUtils;

/**
 * A {@link FileSystemManager} that cleans up the created files and directories on JVM shutdown. It is useful for
 * testing purposes to avoid leaving temporary files on the file system after the tests are done.
 */
public class TestFileSystemManager extends FileSystemManager {

    public TestFileSystemManager(@NonNull final Path rootLocation) {
        super(rootLocation);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> rethrowIO(() -> FileUtils.deleteDirectory(rootPath))));
    }
}
