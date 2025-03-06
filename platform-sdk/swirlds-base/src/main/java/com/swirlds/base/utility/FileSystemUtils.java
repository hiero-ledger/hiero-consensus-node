// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.utility;

import static com.swirlds.base.utility.Retry.DEFAULT_RETRY_DELAY;
import static com.swirlds.base.utility.Retry.DEFAULT_WAIT_TIME;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Provides file system-related utility methods.
 */
public final class FileSystemUtils {

    /**
     * Private constructor to prevent utility class instantiation.
     */
    private FileSystemUtils() {}

    /**
     * Evaluates a given {@code path} to ensure it exists.
     *
     * <p>
     * This method includes retry logic to handle files which may not yet exist but will become available within a
     * given threshold. Examples of these cases are Kubernetes projected volumes and persistent volumes.
     *
     * <p>
     * This overloaded method uses a default wait time of {@link Retry#DEFAULT_WAIT_TIME} and a default retry delay of
     * {@link Retry#DEFAULT_RETRY_DELAY}.
     *
     * @param path the path to check for existence.
     * @return true if the file exists before the retries have been exhausted; otherwise false.
     * @throws NullPointerException if the {@code path} argument is a {@code null} value.
     */
    public static boolean waitForPathPresence(@NonNull final Path path) {
        return waitForPathPresence(path, DEFAULT_WAIT_TIME);
    }

    /**
     * Evaluates a given {@code path} to ensure it exists.
     *
     * <p>
     * This method includes retry logic to handle files which may not yet exist but will become available within a
     * given threshold. Examples of these cases are Kubernetes projected volumes and persistent volumes.
     *
     * <p>
     * This overloaded method uses a default retry delay of {@link Retry#DEFAULT_RETRY_DELAY}.
     *
     * @param path the path to check for existence.
     * @param waitTime the maximum amount of time to wait for the file, directory, block device, or symlink to become available.
     * @return true if the file exists before the retries have been exhausted; otherwise false.
     * @throws NullPointerException if the {@code path} argument is a {@code null} value.
     */
    public static boolean waitForPathPresence(@NonNull final Path path, @NonNull final Duration waitTime) {
        return waitForPathPresence(path, waitTime, DEFAULT_RETRY_DELAY);
    }

    /**
     * Evaluates a given {@code path} to ensure it exists.
     *
     * <p>
     * This method includes retry logic to handle files which may not yet exist but will become available within a
     * given threshold. Examples of these cases are Kubernetes projected volumes and persistent volumes.
     *
     * @param path       the path to check for existence.
     * @param waitTime   the maximum amount of time to wait for the file, directory, block device, or symlink to become available.
     * @param retryDelay the delay between retry attempts.
     * @return true if the file exists before the retries have been exhausted; otherwise false.
     * @throws IllegalArgumentException if the {@code waitTime} argument is less than or equal to zero (0) or the
     *                                 {@code retryDelay} argument is less than or equal to zero (0).
     * @throws NullPointerException     if the {@code path} argument is a {@code null} value.
     */
    public static boolean waitForPathPresence(
            @NonNull final Path path, @NonNull final Duration waitTime, @NonNull final Duration retryDelay) {
        Objects.requireNonNull(path, "path must not be null");
        try {
            return Retry.check(FileSystemUtils::checkForPathPresenceInternal, path, waitTime, retryDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Internal method to evaluate a given {@code path} to ensure it is present.
     *
     * @param path the path to check for existence.
     * @return true if the file, directory, block device, or symlink exists and is not empty if the path references a
     * file; otherwise false.
     */
    private static boolean checkForPathPresenceInternal(@NonNull final Path path) {
        // If the path does not exist, then we should consider it not present
        if (!Files.exists(path)) {
            return false;
        }

        if (Files.isRegularFile(path)) {
            try {
                // If the path exists and is a file, then we must check the size to ensure it is not empty
                return Files.size(path) > 0;
            } catch (IOException ignored) {
                // If an exception occurs while checking the file size, then we should consider the file not present
                return false;
            }
        }

        // If the path exists and is a directory, block device or symlink, then we can consider it present
        return true;
    }
}
