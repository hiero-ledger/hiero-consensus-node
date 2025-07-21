package org.hiero.otter.fixtures.container;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.WatchKey;
import java.util.concurrent.Executor;
import org.hiero.otter.fixtures.internal.result.AbstractMarkerFileObserver;

/**
 * An observer that watches for marker files written by a containerized node.
 * It uses an executor to run the watch loop in a separate thread.
 */
public class ContainerMarkerFileObserver extends AbstractMarkerFileObserver {

    private final Executor executor;

    /**
     * Constructs a new observer for the given node ID and executor.
     *
     * @param nodeId   the ID of the node to observe
     * @param executor the executor to run the watch loop
     */
    public ContainerMarkerFileObserver(@NonNull final NodeId nodeId, @NonNull final Executor executor) {
        super(nodeId);
        this.executor = requireNonNull(executor);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStartObserving() {
        executor.execute(this::watchLoop);
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

            evaluateWatchKey(key);

            // Reset the key to receive further events
            // If the key is not valid, the directory is inaccessible so exit the loop.
            final boolean valid = key.reset();
            if (!valid) {
                break;
            }
        }
    }
}
