// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.emitter;

import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.event.generator.GraphGenerator;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits events from a graph created by a {@link GraphGenerator}.
 */
public interface EventEmitter {

    /**
     * Emits an event from the graph, possibly in a different order than the events were created.
     *
     * @return an event
     */
    EventImpl emitEvent();

    /**
     * Get the next sequence of events.
     *
     * @param numberOfEvents
     * 		The number of events to get.
     */
    default List<EventImpl> emitEvents(final int numberOfEvents) {
        final List<EventImpl> events = new ArrayList<>(numberOfEvents);
        for (int i = 0; i < numberOfEvents; i++) {
            events.add(emitEvent());
        }
        return events;
    }

    /**
     * Returns the graph generator that created the graph this emitter emits events from.
     *
     * @return the graph generator
     */
    GraphGenerator getGraphGenerator();

    /**
     * Get the total number of events that have been emitted by this generator.
     */
    long getNumEventsEmitted();

    /**
     * Set a checkpoint.
     *
     * Two emitters with the same graph generator and with the same checkpoint are guaranteed to emit the same events
     * (in potentially different orders) when the number of events emitted are equal to the checkpoint. For events
     * emitted after the checkpoint has been reached, no special guarantees are provided.
     *
     * It is ok to specify more than one checkpoint during the lifetime of a generator.
     */
    void setCheckpoint(long checkpoint);

    /**
     * Reset this emitter to its original state. Does not undo settings changes, just the events that have been
     * generated and emitted.
     */
    void reset();

    /**
     * Returns a copy of this object as it was first created.
     */
    EventEmitter cleanCopy();

    /**
     * Get a clean copy but with a different seed.
     *
     * @param seed
     * 		The new seed to use.
     */
    EventEmitter cleanCopy(long seed);

    /**
     * Get an exact copy of this event emitter in its current state. The events emitted by this
     * new emitter will be equivalent to the events returned by this emitter, but these events
     * will be distinct objects that are not inter-connected in any way.
     *
     * Note: if this emitter has emitted a large number of events, this method may be expensive. The copied
     * emitter needs to skip all events already emitted.
     */
    default EventEmitter copy() {
        final EventEmitter emitter = cleanCopy();
        emitter.skip(getNumEventsEmitted());
        return emitter;
    }

    /**
     * Skip a number of events. These events will not be returned by any methods.
     *
     * @param numberToSkip
     * 		The number of events to skip.
     */
    default void skip(final long numberToSkip) {
        for (long i = 0; i < numberToSkip; i++) {
            emitEvent();
        }
    }
}
