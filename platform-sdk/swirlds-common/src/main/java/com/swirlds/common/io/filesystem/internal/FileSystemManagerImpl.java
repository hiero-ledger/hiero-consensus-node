// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.filesystem.internal;

import static com.swirlds.common.io.utility.FileUtils.rethrowIO;
import static java.nio.file.Files.exists;

import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the file system operations and organizes file creation within a specified root directory.
 * <p>
 * This implementation of {@link FileSystemManager} organizes file creation within a specified root directory in the
 * following structure:
 * <pre>
 * root
 * ├── USER
 * └── TMP
 * </pre>
 * The name of the directories can be provided by configuration
 * <p>
 * If the root directory already exists, it is used. Otherwise, it is created. Similarly, if the 'USER' directory
 * already exists, it is used; otherwise, it is created. The 'TMP' directory is always recreated.
 * <p>
 * All {@link Path}s provided by this class are handled within the same filesystem as indicated by the
 * {@code rootLocation} parameter.
 * </p>
 * <p>
 * Note: Two different instances of {@link FileSystemManagerImpl} created on the same root location can create paths using the same name.
 * </p>
 */
public class FileSystemManagerImpl implements FileSystemManager {

    private final Path rootPath;
    private final Path tempPath;
    private final Path savedPath;
    private final AtomicLong tmpFileNameIndex = new AtomicLong(0);
    /**
     * Creates a {@link FileSystemManager} and a {@link com.swirlds.common.io.utility.RecycleBin} by searching {@code root}
     * path in the {@link Configuration} class using
     * {@code FileSystemManagerConfig} record
     *
     * @param rootLocation      the location to be used as root path. It should not exist.
     * @param dataDirName       the name of the user data file directory
     * @param tmpDirName        the name of the tmp file directory
     * @param recycleBin       for building the recycle bin.
     * @throws UncheckedIOException if the dir structure to rootLocation cannot be created
     */
    FileSystemManagerImpl(
            @NonNull final String rootLocation,
            final String dataDirName,
            final String tmpDirName,
            @NonNull final RecycleBin recycleBin) {
        this.rootPath = Path.of(rootLocation).normalize();
        if (!exists(rootPath)) {
            rethrowIO(() -> Files.createDirectories(rootPath));
        }

        this.tempPath = rootPath.resolve(tmpDirName);
        this.savedPath = rootPath.resolve(dataDirName);

        if (!exists(savedPath)) {
            rethrowIO(() -> Files.createDirectory(savedPath));
        }
        if (exists(tempPath)) {
            rethrowIO(() -> FileUtils.deleteDirectory(tempPath));
        }
        rethrowIO(() -> Files.createDirectory(tempPath));
    }

    /**
     * Creates a {@link FileSystemManager} and a {@link com.swirlds.common.io.utility.RecycleBin} by searching {@code root}
     * path in the {@link Configuration} class using
     * {@code FileSystemManagerConfig} record
     *
     * @param rootLocation      the location to be used as root path. It should not exist.
     * @param dataDirName       the name of the user data file directory
     * @param tmpDirName        the name of the tmp file directory
     * @throws UncheckedIOException if the dir structure to rootLocation cannot be created
     */
    public FileSystemManagerImpl(
            @NonNull final String rootLocation, @NonNull final String dataDirName, @NonNull final String tmpDirName) {
        this.rootPath = Path.of(rootLocation).normalize();
        if (!exists(rootPath)) {
            rethrowIO(() -> Files.createDirectories(rootPath));
        }

        this.tempPath = rootPath.resolve(tmpDirName);
        this.savedPath = rootPath.resolve(dataDirName);

        if (!exists(savedPath)) {
            rethrowIO(() -> Files.createDirectory(savedPath));
        }
        if (exists(tempPath)) {
            rethrowIO(() -> FileUtils.deleteDirectory(tempPath));
        }
        rethrowIO(() -> Files.createDirectory(tempPath));
    }

    /**
     * Resolve a path relative to the{@code savedPath} of this file system manager.
     *
     * @param relativePath the path to resolve against the root directory
     * @return the resolved path
     * @throws IllegalArgumentException if the path is "above" the root directory (e.g. resolve("../foo")
     */
    @NonNull
    @Override
    public Path resolve(@NonNull final Path relativePath) {
        return requireValidSubPathOf(savedPath, savedPath.resolve(relativePath));
    }

    /**
     * Creates a path relative to the {@code tempPath} directory of the file system manager.
     * There is no file or directory actually being created after the invocation of this method.
     * All calls to this method will return a different path even if {@code tag} is not set.
     * A separate instance pointing to the same {@code rootPath} can create the same paths and should be managed outside this class.
     *
     * @param tag if indicated, will be suffixed to the returned path
     * @return the resolved path
     * @throws IllegalArgumentException if the path is "above" the root directory (e.g. resolve("../foo")
     */
    @NonNull
    @Override
    public Path resolveNewTemp(@Nullable final String tag) {
        final StringBuilder nameBuilder = new StringBuilder();
        nameBuilder.append(System.currentTimeMillis());
        nameBuilder.append(tmpFileNameIndex.getAndIncrement());
        if (tag != null) {
            nameBuilder.append("-");
            nameBuilder.append(tag);
        }

        return requireValidSubPathOf(tempPath, tempPath.resolve(nameBuilder.toString()));
    }

    /**
     * Checks that the specified {@code path} reference is "below" {@code parent} and is not {@code parent} itself.
     * throws IllegalArgumentException if this condition is not true.
     *
     * @param parent the path to check against.
     * @param path   the path to check if is
     * @return {@code path} if it represents a valid path inside {@code parent}
     * @throws IllegalArgumentException if the reference is "above" {@code parent} or is {@code parent} itself
     */
    @NonNull
    private static Path requireValidSubPathOf(@NonNull final Path parent, @NonNull final Path path) {
        final Path relativePath = parent.relativize(path);
        // Check if path is not parent itself and if is contained in parent
        if (relativePath.startsWith("") || relativePath.startsWith("..")) {
            throw new IllegalArgumentException(
                    "Requested path is cannot be converted to valid relative path inside of:" + parent);
        }
        return path;
    }
}
