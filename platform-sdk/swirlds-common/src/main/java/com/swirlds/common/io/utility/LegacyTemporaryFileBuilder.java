// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.utility;

import static com.swirlds.common.io.utility.FileUtils.deleteDirectoryAndLog;
import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static java.nio.file.Files.exists;
import static java.util.Objects.requireNonNull;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;

/**
 * This class provides utility methods for constructing temporary files.
 *
 * @deprecated use the {@link com.swirlds.common.io.filesystem.FileSystemManager} provided
 * by {@link com.swirlds.common.context.PlatformContext} instead
 */
@Deprecated
public final class LegacyTemporaryFileBuilder {

    private LegacyTemporaryFileBuilder() {}

    private static long nextFileId = 0;
    private static Path temporaryFileLocation = null;

    /**
     * Get the directory that holds all temporary files created by this utility.
     *
     * @param configuration platform configuration
     * @return a directory where temporary files are stored
     */
    public static synchronized Path getTemporaryFileLocation(final @NonNull Configuration configuration)
            throws IOException {
        requireNonNull(configuration);

        if (temporaryFileLocation == null) {
            final TemporaryFileConfig config = configuration.getConfigData(TemporaryFileConfig.class);
            final StateCommonConfig stateConfig = configuration.getConfigData(StateCommonConfig.class);
            overrideTemporaryFileLocation(getAbsolutePath(config.getTemporaryFilePath(stateConfig)));
        }

        return temporaryFileLocation;
    }

    /**
     * <p>
     * Update the location where temporary files are written. This method may be useful when writing unit tests.
     * </p>
     *
     * <p>
     * WARNING! Calling this method will cause any files in the original location to be deleted. It's ok to
     * set the temporary file location to something like "/myTemporaryFiles" or "~/myTemporaryFiles". If it's set
     * to something like "/" or "~/", EVERYTHING IN THOSE DIRECTORIES WILL BE DELETED!
     * </p>
     *
     * @param newTemporaryFileLocation
     * 		the new location where temporary files will be written
     */
    public static synchronized void overrideTemporaryFileLocation(final Path newTemporaryFileLocation)
            throws IOException {

        temporaryFileLocation = newTemporaryFileLocation.toAbsolutePath().normalize();
        if (exists(temporaryFileLocation)) {
            deleteDirectoryAndLog(temporaryFileLocation);
        }
        Files.createDirectories(temporaryFileLocation);
    }

    /**
     * Return a temporary file. File will not exist when this method returns. File is guaranteed to have a unique
     * name. File will not be automatically deleted until this JVM is restarted.
     *
     * @param configuration platform configuration
     * @return a new temporary file
     * @deprecated use {@link com.swirlds.common.io.filesystem.FileSystemManager#resolveNewTemp(String)} instead.
     */
    @Deprecated
    public static synchronized Path buildTemporaryFile(final @NonNull Configuration configuration) throws IOException {
        requireNonNull(configuration);
        return buildTemporaryFile(null, configuration);
    }

    /**
     * Return a temporary file. File will not exist when this method returns. File is guaranteed to have a unique
     * name. File will not be automatically deleted until this JVM is restarted.
     *
     * @param postfix
     * 		an optional postfix, helps to make temporary file directory easier to understand
     * 		if a human ever looks at it directly. Ignored if null.
     * @param configuration platform configuration
     * @return a new temporary file
     * @deprecated use {@link com.swirlds.common.io.filesystem.FileSystemManager#resolveNewTemp(String)} instead.
     */
    @Deprecated
    public static synchronized Path buildTemporaryFile(final String postfix, final @NonNull Configuration configuration)
            throws IOException {
        requireNonNull(configuration);

        final String fileName = nextFileId + (postfix == null ? "" : ("-" + postfix));
        nextFileId++;

        final Path temporaryFile = getTemporaryFileLocation(configuration).resolve(fileName);
        if (exists(temporaryFile)) {
            throw new IOException("Name collision for temporary file " + temporaryFile);
        }

        return temporaryFile;
    }

    /**
     * Return a temporary directory. Directory will exist when this method returns.
     * Directory is guaranteed to have a unique name.
     * Directory will not be automatically deleted until this JVM is restarted.
     *
     * @param configuration platform configuration
     * @return a new temporary directory
     * @deprecated use {@link com.swirlds.common.io.filesystem.FileSystemManager#resolveNewTemp(String)} instead
     * and then create a directory using {@link Files#createDirectory(Path, FileAttribute[])}
     */
    @Deprecated
    public static synchronized Path buildTemporaryDirectory(final @NonNull Configuration configuration)
            throws IOException {
        requireNonNull(configuration);
        return buildTemporaryDirectory(null, configuration);
    }

    /**
     * Return a temporary directory. Directory will exist when this method returns.
     * Directory is guaranteed to have a unique name.
     * Directory will not be automatically deleted until this JVM is restarted.
     *
     * @param postfix
     * 		an optional postfix, helps to make temporary file directory easier to understand
     * 		if a human ever looks at it directly. Ignored if null.
     * @param configuration platform configuration
     * @return a new temporary directory
     * @deprecated use {@link com.swirlds.common.io.filesystem.FileSystemManager#resolveNewTemp(String)} instead
     * and then create a directory using {@link Files#createDirectory(Path, FileAttribute[])}
     */
    @Deprecated
    public static synchronized Path buildTemporaryDirectory(
            final String postfix, final @NonNull Configuration configuration) throws IOException {
        requireNonNull(configuration);

        final Path directory = buildTemporaryFile(postfix, configuration);
        if (!exists(directory)) {
            Files.createDirectories(directory);
        }
        return directory;
    }
}
