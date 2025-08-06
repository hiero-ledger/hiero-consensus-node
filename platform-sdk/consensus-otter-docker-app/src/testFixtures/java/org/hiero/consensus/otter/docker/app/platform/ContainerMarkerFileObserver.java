// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.platform;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import org.hiero.otter.fixtures.internal.helpers.MarkerFileUtils;

/**
 * An observer that watches for marker files written by a containerized node.
 * It uses an executor to run the watch loop in a separate thread.
 */
public class ContainerMarkerFileObserver {

    private final List<MarkerFileListener> listeners = new CopyOnWriteArrayList<>();
    private final WatchService watchService;

    /**
     * Constructs a new observer for the given node ID and executor.
     *
     * @param executor the executor to run the watch loop
     * @param markerFilesDir the directory where marker files are written
     */
    public ContainerMarkerFileObserver(@NonNull final Executor executor, @NonNull final Path markerFilesDir) {
        watchService = MarkerFileUtils.startObserving(markerFilesDir);
        executor.execute(this::watchLoop);
    }

    /**
     * Add a listener for marker file updates.
     *
     * @param listener the consumer that will receive updates when marker files are created
     */
    public void addListener(@NonNull final MarkerFileListener listener) {
        listeners.add(listener);
    }

    /**
     * Stops observing the file system for marker files.
     */
    public void destroy() {
        MarkerFileUtils.stopObserving(watchService);
    }

    private void watchLoop() {
        boolean valid;
        do {
            try {
                final WatchKey key = watchService.take();
                final List<String> newMarkerFiles = MarkerFileUtils.evaluateWatchKey(key);
                listeners.forEach(subscriber -> subscriber.accept(newMarkerFiles));
                valid = key.reset();
            } catch (final ClosedWatchServiceException ex) {
                valid = false;
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                valid = false;
            }
        } while (valid);
    }
}
