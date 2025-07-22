package org.hiero.otter.fixtures.internal.helpers;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;
import static java.util.Objects.requireNonNull;

import com.swirlds.platform.ConsensusImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.hiero.otter.fixtures.result.MarkerFilesStatus;

/**
 * Helper class that observes marker files for a specific node.
 */
public class MarkerFileUtils {

    /**
     * Start observing the given directory for marker files.
     *
     * @param markerFilesDir the directory to observe for marker files
     * @return a {@link WatchService} that can be used to monitor the directory for changes
     */
    @NonNull
    public static WatchService startObserving(@NonNull final Path markerFilesDir) {
        try {
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
     * @param oldStatus the previous status of marker files
     * @param watchKey the watch key to evaluate
     * @return the last known status of marker files
     */
    @NonNull
    public static MarkerFilesStatus evaluateWatchKey(@NonNull final MarkerFilesStatus oldStatus, @NonNull final WatchKey watchKey) {
        MarkerFilesStatus currentStatus = requireNonNull(oldStatus);
        for (final WatchEvent<?> event: watchKey.pollEvents()) {
            final WatchEvent.Kind<?> kind = event.kind();

            // An OVERFLOW event can occur if events are lost or discarded.
            // Not much we can do about it, so just skip.
            if (kind == OVERFLOW) {
                continue;
            }

            // The filename is the context of the event.
            if (event.context() instanceof final Path path) {
                final String fileName = path.getFileName().toString();
                currentStatus = switch (fileName) {
                    case ConsensusImpl.COIN_ROUND_MARKER_FILE -> currentStatus.withCoinRoundMarkerFile();
                    case ConsensusImpl.NO_SUPER_MAJORITY_MARKER_FILE ->
                            currentStatus.withNoSuperMajorityMarkerFile();
                    case ConsensusImpl.NO_JUDGES_MARKER_FILE -> currentStatus.withNoJudgesMarkerFile();
                    case ConsensusImpl.CONSENSUS_EXCEPTION_MARKER_FILE ->
                            currentStatus.withConsensusExceptionMarkerFile();
                    default -> {
                        try {
                            // Check if the file is an ISS marker file
                            final IssType issType = IssType.valueOf(IssType.class, fileName);
                            yield currentStatus.withISSMarkerFile(issType);
                        } catch (final IllegalArgumentException ex) {
                            // not a known marker file
                            yield currentStatus;
                        }
                    }
                };
            }
        }
        return currentStatus;
    }
}
