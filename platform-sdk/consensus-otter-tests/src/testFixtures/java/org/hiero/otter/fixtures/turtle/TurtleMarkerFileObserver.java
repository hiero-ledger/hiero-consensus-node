package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;
import java.util.function.Consumer;
import org.hiero.otter.fixtures.internal.helpers.MarkerFileUtils;
import org.hiero.otter.fixtures.result.MarkerFilesStatus;
import org.hiero.otter.fixtures.turtle.TurtleTimeManager.TimeTickReceiver;

/**
 * An observer that watches for marker files written by a Turtle node.
 * It checks for new marker files on each time tick, thus making this check deterministic.
 */
public class TurtleMarkerFileObserver implements TimeTickReceiver {

    private final Consumer<MarkerFilesStatus> statusUpdateCallback;

    @Nullable
    private WatchService watchService;

    private MarkerFilesStatus status = MarkerFilesStatus.INITIAL_STATUS;

    /**
     * Creates a new instance of {@link TurtleMarkerFileObserver}.
     *
     * @param statusUpdateCallback the callback to invoke when the status of marker files changes
     */
    public TurtleMarkerFileObserver(
            final Consumer<MarkerFilesStatus> statusUpdateCallback) {
        this.statusUpdateCallback = requireNonNull(statusUpdateCallback);
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        if (watchService == null) {
            return; // WatchService is not initialized yet
        }

        try {
            final WatchKey key = watchService.poll();
            if (key.isValid()) {
                final MarkerFilesStatus newStatus = MarkerFileUtils.evaluateWatchKey(status, key);
                if (newStatus != status) {
                    status = newStatus;
                    statusUpdateCallback.accept(status);
                }
                return;
            }
        } catch (final ClosedWatchServiceException e) {
            // ignore
        }
        watchService = null;
    }
}
