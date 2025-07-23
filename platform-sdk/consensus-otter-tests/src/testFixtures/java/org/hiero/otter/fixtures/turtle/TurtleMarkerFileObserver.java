// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;
import java.util.List;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.otter.fixtures.internal.helpers.MarkerFileUtils;
import org.hiero.otter.fixtures.internal.result.NodeResultsCollector;

/**
 * An observer that watches for marker files written by a Turtle node.
 * It checks for new marker files on each time tick, thus making this check deterministic.
 */
public class TurtleMarkerFileObserver implements TimeTickReceiver {

    private final NodeResultsCollector resultsCollector;

    @Nullable
    private WatchService watchService;

    /**
     * Creates a new instance of {@link TurtleMarkerFileObserver}.
     *
     * @param resultsCollector the {@link NodeResultsCollector} that collects the results
     */
    public TurtleMarkerFileObserver(@NonNull final NodeResultsCollector resultsCollector) {
        this.resultsCollector = requireNonNull(resultsCollector);
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
            return; // WatchService is not set up
        }

        try {
            final WatchKey key = watchService.poll();
            if (key == null) {
                return; // No events to process
            }
            if (key.isValid()) {
                final List<String> newMarkerFiles = MarkerFileUtils.evaluateWatchKey(key);
                resultsCollector.addMarkerFiles(newMarkerFiles);
                key.reset();
                return;
            }
        } catch (final ClosedWatchServiceException e) {
            // ignore
        }
        watchService = null;
    }
}
