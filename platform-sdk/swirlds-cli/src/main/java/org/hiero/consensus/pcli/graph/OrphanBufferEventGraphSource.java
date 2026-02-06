// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.graph;

import com.swirlds.common.context.PlatformContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedList;
import java.util.List;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.gossip.impl.gossip.NoOpIntakeEventCounter;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;

/**
 * A wrapper source that processes events from an underlying source through the hasher and orphan buffer,
 * but does NOT run them through consensus.
 *
 * <p>This source processes events lazily through:
 * <ol>
 *   <li>Event hasher - computes the hash for each event</li>
 *   <li>Orphan buffer - links parents, computes ngen, filters orphans</li>
 * </ol>
 *
 * <p>Events that are released from the orphan buffer (i.e., have their parents linked) are returned.
 * Events that remain orphaned (missing parents) are not included in the output.
 * Events that became ancient are not included in the output.
 */
public class OrphanBufferEventGraphSource implements EventGraphSource {

    private final EventGraphSource underlyingSource;
    private final PbjStreamHasher eventHasher;
    private final DefaultOrphanBuffer orphanBuffer;

    /** Buffer of events released from orphan buffer ready to be returned. */
    private final LinkedList<PlatformEvent> releasedEventsBuffer;

    /**
     * Creates a source that wraps an underlying source and processes events through hasher and orphan buffer.
     *
     * @param underlyingSource  the underlying source providing raw events
     * @param context           platform context for configuration and metrics
     */
    public OrphanBufferEventGraphSource(
            @NonNull final EventGraphSource underlyingSource, @NonNull final PlatformContext context) {

        this.underlyingSource = underlyingSource;
        this.eventHasher = new PbjStreamHasher();
        this.orphanBuffer = new DefaultOrphanBuffer(context.getMetrics(), new NoOpIntakeEventCounter());
        this.releasedEventsBuffer = new LinkedList<>();
    }

    /**
     * @return non-ancient, non-orphaned events in topological order, hashed, with ngen computed and parents linked.
     */
    @Override
    @NonNull
    public PlatformEvent next() {
        storeOneIntoBufferIfPossible();

        if (releasedEventsBuffer.isEmpty()) {
            throw new java.util.NoSuchElementException("No more events available");
        }

        return releasedEventsBuffer.removeFirst();
    }

    @Override
    public boolean hasNext() {
        storeOneIntoBufferIfPossible();
        return !releasedEventsBuffer.isEmpty();
    }

    /**
     * Processes events from the underlying source until at least one event is released to the buffer,
     * or the underlying source is exhausted. This ensures a consistent contract for EventSource.
     */
    private void storeOneIntoBufferIfPossible() {
        while (releasedEventsBuffer.isEmpty() && underlyingSource.hasNext()) {
            processNextEvent();
        }
    }

    /**
     * Processes one event from the underlying source through the hasher and orphan buffer.
     * Any released events are added to the buffer.
     */
    private void processNextEvent() {
        final PlatformEvent event = underlyingSource.next();

        // Hash the event
        eventHasher.hashEvent(event);

        // Process through orphan buffer - may release events with linked parents
        final List<PlatformEvent> releasedEvents = orphanBuffer.handleEvent(event);
        releasedEventsBuffer.addAll(releasedEvents);
    }

    /**
     * Updates the event window in the underlying orphan buffer.
     * This allows the orphan buffer to filter out ancient events.
     *
     * <p>Any events released by the orphan buffer as a result of the event window update
     * are added to the internal buffer and will be returned by subsequent calls to {@link #next()}.
     *
     * @param eventWindow the new event window
     */
    public void setEventWindow(@NonNull final EventWindow eventWindow) {
        final List<PlatformEvent> releasedEvents = orphanBuffer.setEventWindow(eventWindow);
        releasedEventsBuffer.addAll(releasedEvents);
    }
}
