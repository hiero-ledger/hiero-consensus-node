/*
 * Copyright (C) 2020-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.test.event.emitter;

import com.swirlds.platform.internal.EventImpl;
import java.util.LinkedList;
import java.util.List;

/**
 * This event emitter wraps another event emitter. It emits the exact same events as the inner emitter,
 * but also keeps a list of all emitted events that can be retrieved later.
 */
public class CollectingEventEmitter extends AbstractEventEmitter {

    /** All emitted events */
    private final LinkedList<EventImpl> collectedEvents;

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
    public EventImpl emitEvent() {
        final EventImpl event = emitter.emitEvent();
        collectedEvents.add(event);
        numEventsEmitted++;
        return event;
    }

    /**
     * Returns all collected events.
     */
    public List<EventImpl> getCollectedEvents() {
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
