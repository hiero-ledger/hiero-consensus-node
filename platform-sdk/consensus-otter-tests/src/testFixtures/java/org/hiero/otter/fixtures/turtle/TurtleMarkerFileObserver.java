// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;
import org.hiero.otter.fixtures.internal.helpers.MarkerFileUtils;
import org.hiero.otter.fixtures.internal.result.NodeResultsCollector;

/**
 * An observer that watches for marker files written by a Turtle node.
 * It checks for new marker files on each time tick.
 * <p>
 * On macOS, the WatchService implementation has known reliability issues (polling-based with delays).
 * To work around this, we periodically scan the directory as a fallback mechanism to ensure we
 * don't miss any marker files.
 */
public class TurtleMarkerFileObserver implements TimeTickReceiver {

    /** The time interval between fallback directory scans */
    private static final Duration FALLBACK_SCAN_INTERVAL = Duration.ofSeconds(10L);

    private final NodeResultsCollector resultsCollector;

    @Nullable
    private WatchService watchService;

    @Nullable
    private Path markerFilesDir;

    /** The last time (in real time) a fallback scan was performed */
    private Instant lastFallbackScanTime = Instant.now();

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
        this.markerFilesDir = markerFilesDir;
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
        if (watchService == null || markerFilesDir == null) {
            return; // WatchService is not set up
        }

        // Process WatchService events
        try {
            final WatchKey key = watchService.poll();
            if (key != null && key.isValid()) {
                final List<String> newMarkerFiles = MarkerFileUtils.evaluateWatchKey(key);
                resultsCollector.addMarkerFiles(newMarkerFiles);
                key.reset();
            }
        } catch (final ClosedWatchServiceException e) {
            watchService = null;
            return;
        }

        // Fallback: Periodically scan the directory to catch any files missed by WatchService
        // This is especially important on macOS where WatchService has known reliability issues
        final Instant currentRealTime = Instant.now();
        if (Duration.between(lastFallbackScanTime, currentRealTime).compareTo(FALLBACK_SCAN_INTERVAL) >= 0) {
            final List<String> allMarkerFiles = MarkerFileUtils.scanDirectoryForMarkerFiles(markerFilesDir);
            resultsCollector.addMarkerFiles(allMarkerFiles);
            lastFallbackScanTime = currentRealTime;
        }
    }
}
