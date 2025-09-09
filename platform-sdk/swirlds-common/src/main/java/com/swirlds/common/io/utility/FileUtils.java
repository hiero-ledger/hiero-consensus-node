// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.utility;

import static com.swirlds.common.io.utility.LegacyTemporaryFileBuilder.buildTemporaryDirectory;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;
import static java.nio.file.Files.exists;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.util.Objects.requireNonNull;

import com.swirlds.common.io.streams.MerkleDataOutputStream;
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
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility methods for file operations.
 */
public final class FileUtils {

    private static final Logger logger = LogManager.getLogger(FileUtils.class);

    private FileUtils() {}

    /**
     * Syntactic sugar. Runs an operation and rethrows any {@link IOException}s as {@link UncheckedIOException}s.
     *
     * @param runnable an operation to run
     */
    public static void rethrowIO(@NonNull final IORunnable runnable) {
        try {
            runnable.run();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Syntactic sugar. Runs an operation and rethrows any {@link IOException}s as {@link UncheckedIOException}s.
     *
     * @param supplier an operation that supplies a value
     */
    public static <T> @NonNull T rethrowIO(@NonNull final IOSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Get an absolute path to the current working directory, i.e. ".".
     */
    public static @NonNull Path getAbsolutePath() {
        return getAbsolutePath(".");
    }

    /**
     * Get an absolute path to a particular location described by a string, starting in the current working directory.
     * For example, if the current execution directory is "/user/home" and this method is invoked with "foo", then a
     * {@link Path} at "/user/home/foo" is returned. Resolves "~".
     *
     * @param pathDescription a description of the path, e.g. "foo", "/foobar", "foo/bar"
     * @return an absolute Path to the requested location
     */
    public static @NonNull Path getAbsolutePath(@NonNull final String pathDescription) {
        final String expandedPath = pathDescription.replaceFirst("^~", System.getProperty("user.home"));
        return FileSystems.getDefault().getPath(expandedPath).toAbsolutePath().normalize();
    }

    /**
     * Get an absolute path to a particular location described by a string, starting in the current working directory.
     * For example, if the current execution directory is "/user/home" and this method is invoked with "foo", then a
     * {@link Path} at "/user/home/foo" is returned. Resolves "~".
     *
     * @param path a non-absolute path
     * @return an absolute Path to the requested location
     */
    public static @NonNull Path getAbsolutePath(@NonNull final Path path) {
        return getAbsolutePath(path.toString());
    }

    /**
     * Delete a directory and all files contained in this directory. If there is a problem deleting a file
     * or subdirectory, no more files or directories are attempted to be deleted.
     *
     * @param directoryToBeDeleted the directory to be deleted
     */
    public static void deleteDirectory(@NonNull final Path directoryToBeDeleted) throws IOException {
        if (Files.isDirectory(directoryToBeDeleted)) {
            try (final Stream<Path> list = Files.list(directoryToBeDeleted)) {
                final Iterator<Path> children = list.iterator();
                while (children.hasNext()) {
                    deleteDirectory(children.next());
                }
            }
            Files.delete(directoryToBeDeleted);
        } else {
            Files.deleteIfExists(directoryToBeDeleted);
        }
    }

    /**
     * Similar to {@link #deleteDirectory(Path)} but with additional logging. If there is a problem deleting a file or
     * subdirectory, no more files or directories are attempted to be deleted.
     *
     * @param directoryToBeDeleted the directory to be deleted
     */
    public static void deleteDirectoryAndLog(@NonNull final Path directoryToBeDeleted) throws IOException {
        logger.info(STATE_TO_DISK.getMarker(), "deleting directory {}", directoryToBeDeleted);

        try {
            deleteDirectory(directoryToBeDeleted);
        } catch (final Exception e) {
            logger.error(EXCEPTION.getMarker(), "failed to delete directory {}", directoryToBeDeleted);
            throw e;
        }
    }

    /**
     * Create a shallow copy of a directory structure using hard links. Resulting directory structure will contain the
     * same files. Modifying files in the new directory will also modify the corresponding files in the original
     * directory. Deleting files in the new directory has no effect on the old directory. Adding files to the new
     * directory has no effect on the old directory.
     *
     * @param source      the directory structure to copy, is legal for this argument to be a regular file
     * @param destination the location where the directory structure will be placed, assumed that no file or directory
     *                    already exists in this location. Note that the root of the copied directory structure will be
     *                    placed at this destination, and not in this destination.
     * @throws UncheckedIOException if there is a problem hard linking the tree
     */
    public static void hardLinkTree(@NonNull final Path source, @NonNull final Path destination) throws IOException {
        if (!exists(source)) {
            throw new IOException(source + " does not exist or can not be accessed");
        }

        if (exists(destination)) {
            throw new IOException(destination + " already exists");
        }

        // Recreate the directory tree
        try (final Stream<Path> files = Files.walk(source)) {
            files.filter(Files::isDirectory).forEach((final Path originalDirectoryPath) -> {
                final Path relativeDirectoryPath = source.relativize(originalDirectoryPath);
                final Path newDirectoryPath = destination.resolve(relativeDirectoryPath);
                try {
                    Files.createDirectories(newDirectoryPath);
                } catch (final IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }

        // Hard link files
        try (final Stream<Path> files = Files.walk(source)) {
            files.filter(Files::isRegularFile).forEach((final Path originalFilePath) -> {
                final Path relativeFilePath = source.relativize(originalFilePath);
                final Path newFilePath = destination.resolve(relativeFilePath);
                try {
                    Files.createLink(newFilePath, originalFilePath);
                } catch (final IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
    }

    /**
     * Throw an exception if any of an array of files already exists.
     *
     * @param files an array of files
     * @throws IOException if any of the files exist
     */
    public static void throwIfFileExists(@Nullable final Path... files) throws IOException {
        if (files == null) {
            return;
        }
        for (final Path file : files) {
            if (exists(file)) {
                throw new FileAlreadyExistsException("File " + file + " already exists");
            }
        }
    }

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
            @NonNull final IOConsumer<Path> operation,
            @NonNull final Configuration configuration)
            throws IOException {
        requireNonNull(directory);
        // don't null check operation as FileUtilsTests#executeAndRename expects IOException
        requireNonNull(configuration);
        executeAndRename(directory, buildTemporaryDirectory(configuration), operation);
    }

    /**
     * Execute an operation that writes to a directory. When the operation is complete, rename the directory. Useful for
     * file operations that need to be atomic.
     *
     * @param directory    the name of directory after it is renamed
     * @param tmpDirectory the name of the temporary directory, if it does not exist then it is created
     * @param operation    an operation that writes to a directory
     */
    public static void executeAndRename(
            @NonNull final Path directory, @NonNull final Path tmpDirectory, @NonNull final IOConsumer<Path> operation)
            throws IOException {

        try {
            throwIfFileExists(directory);
            if (!exists(tmpDirectory)) {
                Files.createDirectories(tmpDirectory);
            }
            if (!Files.exists(directory.getParent())) {
                Files.createDirectories(directory.getParent());
            }

            operation.accept(tmpDirectory);

            // Move needs to be atomic to guarantee that the folder only exists when its contents are complete.
            // Otherwise, it's possible another thread will see a half-completed directory.
            Files.move(tmpDirectory, directory, ATOMIC_MOVE);
        } catch (final Throwable ex) {
            logger.info(STATE_TO_DISK.getMarker(), "deleting temporary file due to exception");
            throw ex;
        } finally {
            try {
                deleteDirectory(tmpDirectory);
            } catch (final Throwable t) {
                logger.warn(
                        STATE_TO_DISK.getMarker(),
                        "unable to delete temporary directory {} due to {}",
                        tmpDirectory,
                        t);
            }
        }
    }

    /**
     * Write to a new file, and make sure it's flushed to disk before returning.
     *
     * @param file        the file to be written to, should not exist prior to this method being called
     * @param writeMethod the method that writes
     */
    public static void writeAndFlush(
            @NonNull final Path file, @NonNull final IOConsumer<MerkleDataOutputStream> writeMethod)
            throws IOException {

        throwIfFileExists(file);

        try (final FileOutputStream fileOut = new FileOutputStream(file.toFile());
                final BufferedOutputStream bufOut = new BufferedOutputStream(fileOut);
                final MerkleDataOutputStream out = new MerkleDataOutputStream(bufOut)) {

            writeMethod.accept(out);

            // flush all the data to the file stream
            out.flush();
            // make sure the data is actually written to disk
            fileOut.getFD().sync();
        }
    }

    /**
     * Returns the user directory path specified by the {@code user.dir} system property.
     *
     * @return the user directory path
     */
    public static @NonNull String getUserDir() {
        return System.getProperty("user.dir");
    }

    /**
     * Find files in the given directory that end with the given suffix.
     * @param dir the directory to search
     * @param suffix the suffix to match
     * @return a list of paths to files that match the given suffix
     * @throws IOException if there is a problem walking the directory
     */
    public static @NonNull List<Path> findFiles(@NonNull final Path dir, @NonNull final String suffix)
            throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(suffix))
                    .toList();
        }
    }

    /**
     * Delete files in the given directory that end with the given suffix.
     * @param dir the directory to search
     * @param suffix the suffix to match
     * @throws IOException if there is a problem walking the directory or deleting
     */
    public static void deleteFiles(@NonNull final Path dir, @NonNull final String suffix) throws IOException {
        final List<Path> files = findFiles(dir, suffix);
        for (final Path file : files) {
            Files.delete(file);
        }
    }

    /**
     * Moves a source directory, along with its contents, to a target location.
     * If the target directory does not exist, it will be created.
     * The method preserves the directory structure during the move operation.
     * After all files and subdirectories are moved, the source directory is deleted.
     * <br>
     * <strong>Note:</strong> if a file already exists at the target with the same relative path, it will be overwritten.
     *
     * @param source the path of the directory to be moved, must not be null
     * @param target the path where the directory should be moved, must not be null
     * @throws IOException if the source does not exist or an I/O error occurs during the operation
     */
    public static void moveDirectory(@NonNull final Path source, @NonNull final Path target) throws IOException {
        Objects.requireNonNull(source, "Source path cannot be null");
        Objects.requireNonNull(target, "Target path cannot be null");

        if (!Files.exists(source)) {
            throw new IOException(source + " does not exist");
        }
        if (!Files.exists(target)) {
            Files.createDirectories(target);
        }

        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @NonNull
            @Override
            public FileVisitResult preVisitDirectory(@NonNull final Path dir, @NonNull final BasicFileAttributes attrs)
                    throws IOException {
                final Path relative = source.relativize(dir);
                final Path targetDir = target.resolve(relative);
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @NonNull
            @Override
            public FileVisitResult visitFile(@NonNull final Path file, @NonNull final BasicFileAttributes attrs)
                    throws IOException {
                final Path relative = source.relativize(file);
                final Path targetFile = target.resolve(relative);
                Files.move(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }

            @NonNull
            @Override
            public FileVisitResult postVisitDirectory(@NonNull final Path dir, @Nullable final IOException exc)
                    throws IOException {
                // Delete the source directory after moving all files
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * copies a source directory, along with its contents, to a target location.
     * If the target directory does not exist, it will be created.
     * The method preserves the directory structure during the copy operation.
     * <br>
     * <strong>Note:</strong> if a file already exists at the target with the same relative path, it will be overwritten.
     *
     * @param source the path of the directory to be moved, must not be null
     * @param target the path where the directory should be moved, must not be null
     * @throws IOException if the source does not exist or an I/O error occurs during the operation
     */
    public static void copyDirectory(@NonNull final Path source, @NonNull final Path target) throws IOException {
        Objects.requireNonNull(source, "Source path cannot be null");
        Objects.requireNonNull(target, "Target path cannot be null");

        if (!Files.exists(source)) {
            throw new IOException(source + " does not exist");
        }
        if (!Files.exists(target)) {
            Files.createDirectories(target);
        }

        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @NonNull
            @Override
            public FileVisitResult preVisitDirectory(@NonNull final Path dir, @NonNull final BasicFileAttributes attrs)
                    throws IOException {
                final Path relative = source.relativize(dir);
                final Path targetDir = target.resolve(relative);
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @NonNull
            @Override
            public FileVisitResult visitFile(@NonNull final Path file, @NonNull final BasicFileAttributes attrs)
                    throws IOException {
                final Path relative = source.relativize(file);
                final Path targetFile = target.resolve(relative);
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Renames a source file represented by source
     *
     * @param source the path of the file to be renamed, must not be null
     * @param newName the new name to give the file, must not be null
     * @throws IOException if the source does not exist or an I/O error occurs during the operation
     */
    public static void rename(@NonNull final Path source, @NonNull final String newName) throws IOException {
        final Path target = requireNonNull(source).resolveSibling(requireNonNull(newName));

        if (Files.exists(target)) {
            throw new FileAlreadyExistsException(target.toString());
        }

        try {
            Files.move(source, target, ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }
}
