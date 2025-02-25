// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Convenience methods for handling logFiles pre and after benchmark runs
 */
public class LogFiles {

    public static final String LOGGING_FOLDER = "logging-out";

    private LogFiles() {}

    /**
     * Provides the path to the log file based on the implementationName of the logging system under benchmark
     * {@code implementationName} and the type of benchmark {@code type}. Previously deleting the file if exists in the
     * FS.
     */
    @NonNull
    public static String provideLogFilePath(
            final @NonNull String implementationName, final @NonNull String type, final @NonNull String mode) {
        final String path = getPath(implementationName, type, mode);
        deleteFile(path);
        return path;
    }

    /**
     * Provides the path to the log file based on the implementation of the logging system under benchmark
     * {@code implementationName} and the type of benchmark {@code type}
     */
    @NonNull
    public static String getPath(final @NonNull String implementation, final @NonNull String type, final String mode) {
        final long pid = ProcessHandle.current().pid();
        return LOGGING_FOLDER + File.separator + "benchmark-" + pid + "-" + implementation + "-" + type + "-" + mode
                + ".log";
    }

    /**
     * Deletes the file
     */
    public static void deleteFile(final @NonNull String logFile) {
        try {
            Files.deleteIfExists(Path.of(logFile));
        } catch (IOException e) {
            throw new RuntimeException("Can not delete old log file", e);
        }
    }

    /**
     * If exists and is possible, remove the {@code LOGGING_FOLDER} dir and all its content
     */
    public static void tryDeleteDirAndContent() {
        final Path path = Path.of(LOGGING_FOLDER);
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .map(File::getAbsolutePath)
                    .forEach(LogFiles::deleteFile);
        } catch (IOException e) {
            // do nothing
        }
    }
}
