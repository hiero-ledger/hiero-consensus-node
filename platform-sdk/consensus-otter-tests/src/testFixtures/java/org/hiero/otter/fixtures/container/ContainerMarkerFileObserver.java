package org.hiero.otter.fixtures.container;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.Executor;
import org.hiero.otter.fixtures.internal.helpers.MarkerFileUtils;

/**
 * An observer that watches for marker files written by a containerized node.
 * It uses an executor to run the watch loop in a separate thread.
 */
public class ContainerMarkerFileObserver {

    private final Executor executor;

    @Nullable
    private WatchService watchService;

    /**
     * Constructs a new observer for the given node ID and executor.
     *
     * @param executor the executor to run the watch loop
     */
    public ContainerMarkerFileObserver(@NonNull final Executor executor) {
        this.executor = requireNonNull(executor);
    }

    /**
     * Starts observing the given directory for marker files.
     *
     * @param markerFilesDir the directory to observe for marker files
     */
    public void startObserving(@NonNull final Path markerFilesDir) {
        if (watchService != null) {
            throw new IllegalStateException("Already observing marker files");
        }
        watchService = MarkerFileUtils.startObserving(markerFilesDir);
        executor.execute(this::watchLoop);
    }

    /**
     * Stops observing the file system for marker files.
     */
    public void stopObserving() {
        if (watchService != null) {
            MarkerFileUtils.stopObserving(watchService);
        }
        watchService = null;
    }

    private void watchLoop() {
        assert watchService != null;
        for (;;) {

            // wait for key to be signaled
            final WatchKey key;
            try {
                key = watchService.take();
            } catch (final ClosedWatchServiceException ex) {
                // The watch service has been closed, exit the loop
                return;
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }

//            evaluateWatchKey(key);

            // Reset the key to receive further events
            // If the key is not valid, the directory is inaccessible so exit the loop.
            final boolean valid = key.reset();
            if (!valid) {
                break;
            }
        }
    }
}
