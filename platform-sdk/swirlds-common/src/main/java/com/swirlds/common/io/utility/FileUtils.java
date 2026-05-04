// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.utility;

import static com.swirlds.common.io.utility.LegacyTemporaryFileBuilder.buildTemporaryDirectory;
import static java.util.Objects.requireNonNull;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Utility methods for file operations.
 */
public final class FileUtils {

    private FileUtils() {}

    /**
     * Execute an operation that writes to a directory. When the operation is complete, rename the directory. Useful for
     * file operations that need to be atomic.
     *
     * @param directory the name of directory after it is renamed
     * @param operation an operation that writes to a directory
     * @param configuration platform configuration
     */
    public static void executeAndRename(
            @NonNull final Path directory,
            @NonNull final org.hiero.base.io.IOConsumer<Path> operation,
            @NonNull final Configuration configuration)
            throws IOException {
        requireNonNull(directory);
        // don't null check operation as FileUtilsTests#executeAndRename expects IOException
        requireNonNull(configuration);
        org.hiero.base.io.FileUtils.executeAndRename(directory, buildTemporaryDirectory(configuration), operation);
    }
}
