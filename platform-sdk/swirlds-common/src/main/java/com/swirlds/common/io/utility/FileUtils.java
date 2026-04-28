// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.utility;

import static com.swirlds.common.io.utility.LegacyTemporaryFileBuilder.buildTemporaryDirectory;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;
import static java.nio.file.Files.exists;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.util.Objects.requireNonNull;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.io.IORunnable;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * Utility methods for file operations.
 */
public final class FileUtils {

    private static final Logger logger = LogManager.getLogger(FileUtils.class);

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
        executeAndRename(directory, buildTemporaryDirectory(configuration), operation);
    }
}
