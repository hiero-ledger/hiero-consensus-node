package org.hiero.otter.fixtures.internal.result;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.ConsensusImpl;
import com.swirlds.platform.config.PathsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.hiero.otter.fixtures.result.MarkerFileSubscriber;
import org.hiero.otter.fixtures.result.MarkerFilesStatus;
import org.hiero.otter.fixtures.result.SingleNodeMarkerFileResult;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * An abstract class that contains functionality common to marker file observers of the different environments.
 * It provides methods to subscribe to marker file updates and start/stop observing.
 */
public abstract class AbstractMarkerFileObserver implements SingleNodeMarkerFileResult {

    private final NodeId nodeId;
    private final List<MarkerFileSubscriber> markerFileSubscribers = new CopyOnWriteArrayList<>();

    private MarkerFilesStatus currentStatus = MarkerFilesStatus.INITIAL_STATUS;

    @Nullable
    protected WatchService watchService;

    /**
     * Constructs a new {@link AbstractMarkerFileObserver} for the given node ID.
     *
     * @param nodeId the ID of the node to observe
     */
    public AbstractMarkerFileObserver(@NonNull final NodeId nodeId) {
        this.nodeId = requireNonNull(nodeId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeId nodeId() {
        return nodeId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MarkerFilesStatus status() {
        return currentStatus;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void subscribe(@NonNull final MarkerFileSubscriber subscriber) {
        markerFileSubscribers.add(subscriber);
    }

    /**
     * Start observing the file system for marker files. The configuration is taken from the provided
     * {@link Configuration} object.
     *
     * @param configuration the configuration containing the paths for marker files
     */
    public void startObserving(@NonNull final Configuration configuration) {
        currentStatus = MarkerFilesStatus.INITIAL_STATUS;
        final PathsConfig pathsConfig = configuration.getConfigData(PathsConfig.class);
        final boolean enabled = pathsConfig.writePlatformMarkerFiles();
        final Path markerFilesDir = pathsConfig.getMarkerFilesDir();
        if (enabled && (markerFilesDir != null) && Files.isDirectory(markerFilesDir)) {
            try {
                watchService = FileSystems.getDefault().newWatchService();
                markerFilesDir.register(watchService, ENTRY_CREATE);
                doStartObserving();
            } catch (final IOException e) {
                throw new UncheckedIOException("Failed to register watch service for marker files", e);
            }
        }
    }

    /**
     * Abstract method to be implemented by subclasses to define the specific behavior when starting to observe marker files.
     */
    protected abstract void doStartObserving();

    /**
     * Stops observing the file system for marker files.
     */
    public void stopObserving() {
        if (watchService != null) {
            try {
                watchService.close();
            } catch (final IOException e) {
                throw new UncheckedIOException("Failed to close watch service", e);
            }
        }
    }

    /**
     * Helper methods that evaluates the given {@link WatchKey} for marker file events.
     *
     * @param watchKey the watch key to evaluate
     */
    protected void evaluateWatchKey(@NonNull final WatchKey watchKey) {
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
                final MarkerFilesStatus newStatus = switch (fileName) {
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
                if (!newStatus.equals(currentStatus)) {
                    currentStatus = newStatus;
                    markerFileSubscribers.removeIf(
                            subscriber -> subscriber.onNewMarkerFile(nodeId, newStatus) == SubscriberAction.UNSUBSCRIBE);
                }
            }
        }
    }
}
