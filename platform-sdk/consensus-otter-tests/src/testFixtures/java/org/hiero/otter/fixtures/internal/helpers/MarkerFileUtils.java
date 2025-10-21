// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.helpers;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper class that observes marker files for a specific node.
 */
public class MarkerFileUtils {

    private MarkerFileUtils() {}

    /**
     * Start observing the given directory for marker files.
     *
     * @param markerFilesDir the directory to observe for marker files
     * @return a {@link WatchService} that can be used to monitor the directory for changes
     */
    @NonNull
    public static WatchService startObserving(@NonNull final Path markerFilesDir) {
        try {
            Files.createDirectories(markerFilesDir);
            final WatchService watchService = FileSystems.getDefault().newWatchService();
            markerFilesDir.register(watchService, ENTRY_CREATE);
            return watchService;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to register watch service for marker files", e);
        }
    }

    /**
     * Stops observing the file system for marker files.
     *
     * @param watchService the watch service to stop observing
     */
    public static void stopObserving(@NonNull final WatchService watchService) {
        try {
            watchService.close();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to close watch service", e);
        }
    }

    /**
     * Evaluate the watchKey and return the last known status of marker files.
     *
     * @param watchKey the watch key to evaluate
     * @return the last known status of marker files
     */
    @NonNull
    public static List<String> evaluateWatchKey(@NonNull final WatchKey watchKey) {
        final List<String> result = new ArrayList<>();
        for (final WatchEvent<?> event : watchKey.pollEvents()) {
            final WatchEvent.Kind<?> kind = event.kind();

            // An OVERFLOW event can occur if events are lost or discarded.
            // Not much we can do about it, so just skip.
            if (kind == OVERFLOW) {
                continue;
            }

            // The filename is the context of the event.
            if (event.context() instanceof final Path path) {
                final String fileName = path.getFileName().toString();
                result.add(fileName);
            }
        }
        return result;
    }

    /**
     * Scans a directory for marker files. This is used as a fallback mechanism when WatchService
     * events are not received, which is particularly important on macOS where the WatchService
     * implementation has known reliability issues.
     *
     * @param markerFilesDir the directory to scan for marker files
     * @return the list of marker file names found in the directory
     */
    @NonNull
    public static List<String> scanDirectoryForMarkerFiles(@NonNull final Path markerFilesDir) {
        try {
            if (!Files.exists(markerFilesDir)) {
                return List.of();
            }
            try (final var stream = Files.list(markerFilesDir)) {
                return stream.filter(Files::isRegularFile)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .toList();
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to scan directory for marker files", e);
        }
    }
}
