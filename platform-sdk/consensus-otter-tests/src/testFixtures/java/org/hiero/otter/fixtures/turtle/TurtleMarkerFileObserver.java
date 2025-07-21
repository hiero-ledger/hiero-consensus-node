package org.hiero.otter.fixtures.turtle;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.WatchKey;
import java.time.Instant;
import org.hiero.otter.fixtures.internal.result.AbstractMarkerFileObserver;
import org.hiero.otter.fixtures.turtle.TurtleTimeManager.TimeTickReceiver;

/**
 * An observer that watches for marker files written by a Turtle node.
 * It checks for new marker files on each time tick, thus making this check deterministic.
 */
public class TurtleMarkerFileObserver extends AbstractMarkerFileObserver implements TimeTickReceiver {

    private enum State { OBSERVING, STOPPED }

    private State state = State.STOPPED;

    /**
     * Constructs a new {@link AbstractMarkerFileObserver} for the given node ID.
     *
     * @param nodeId the ID of the node to observe
     */
    public TurtleMarkerFileObserver(@NonNull final NodeId nodeId) {
        super(nodeId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStartObserving() {
        state = State.OBSERVING;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        if (state == State.STOPPED) {
            return; // Do not process ticks if the observer is stopped
        }

        assert watchService != null;
        try {
            final WatchKey key = watchService.poll();
            if (key.isValid()) {
                evaluateWatchKey(key);
            } else {
                state = State.STOPPED;
            }
        } catch (final ClosedWatchServiceException e) {
            state = State.OBSERVING;
        }
    }
}
