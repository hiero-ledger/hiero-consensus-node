// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter;

import java.util.LinkedList;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * This event emitter wraps another event emitter. It emits the exact same events as the inner emitter,
 * but also keeps a list of all emitted events that can be retrieved later.
 */
public class CollectingEventEmitter extends AbstractEventEmitter {

    /** All emitted events */
    private final LinkedList<PlatformEvent> collectedEvents;

    private final EventEmitter emitter;

    public CollectingEventEmitter(final EventEmitter emitter) {
        super(emitter.getGraphGenerator());
        collectedEvents = new LinkedList<>();
        this.emitter = emitter;
    }

    public CollectingEventEmitter(final CollectingEventEmitter that) {
        this(that.emitter.cleanCopy());
    }

    public CollectingEventEmitter(final CollectingEventEmitter that, final long seed) {
        this(that.emitter.cleanCopy(seed));
    }

    /**
     * Emits an event from the graph, possibly in a different order than the events were created.
     *
     * @return an event
     */
    @Override
    public PlatformEvent emitEvent() {
        final PlatformEvent event = emitter.emitEvent();
        collectedEvents.add(event);
        numEventsEmitted++;
        return event;
    }

    /**
     * Returns all collected events.
     */
    public List<PlatformEvent> getCollectedEvents() {
        return new LinkedList<>(collectedEvents);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        super.reset();
        emitter.reset();
        collectedEvents.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CollectingEventEmitter cleanCopy() {
        return new CollectingEventEmitter(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CollectingEventEmitter cleanCopy(final long seed) {
        return new CollectingEventEmitter(this, seed);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCheckpoint(final long checkpoint) {
        emitter.setCheckpoint(checkpoint);
    }
}
