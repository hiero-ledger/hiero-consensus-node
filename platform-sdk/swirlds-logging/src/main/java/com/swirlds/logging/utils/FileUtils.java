// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.utils;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileUtils {
    private FileUtils() {}

    /**
     * Given a file path, checks if the parent directory exist If it doesn't exist, creates it.
     *
     * @param path the path to check
     * @throws IllegalStateException if the parent dir exists, but it can be written to or exist and is not a directory
     */
    public static void checkOrCreateParentDirectory(final @NonNull Path path) {
        try {
            String pathString = path.toFile().getCanonicalPath();
            File file = new File(pathString);
            File dir = file.getParentFile();

            if (!dir.exists()) {
                Files.createDirectories(dir.toPath());
            } else if (dir.exists() && !dir.isDirectory()) {
                throw new IllegalStateException("Path for Log directory is not a directory. Path:" + dir);
            } else if (!dir.canWrite()) {
                throw new IllegalStateException("Cannot write log directory. Dir:" + dir);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write to log directory. Path:" + path, e);
        }
    }
}
