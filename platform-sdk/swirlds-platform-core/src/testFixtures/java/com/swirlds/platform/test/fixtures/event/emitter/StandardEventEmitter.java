// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.emitter;

import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.event.generator.GraphGenerator;

/**
 * Emits events in the order in which the generator creates them.
 */
public class StandardEventEmitter extends AbstractEventEmitter {

    public StandardEventEmitter(final GraphGenerator graphGenerator) {
        super(graphGenerator);
        reset();
    }

    public StandardEventEmitter(final StandardEventEmitter that) {
        this(that.getGraphGenerator().cleanCopy());
        reset();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventImpl emitEvent() {
        final EventImpl event = getGraphGenerator().generateEvent();
        numEventsEmitted++;
        return event;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StandardEventEmitter cleanCopy() {
        return new StandardEventEmitter(this);
    }

    /**
     * {@inheritDoc}
     * <p>
     * This class does not use randomness to determine the next emitted event, so this method is equivalent to calling
     * {@link #cleanCopy()}.
     * </p>
     */
    @Override
    public StandardEventEmitter cleanCopy(final long seed) {
        return new StandardEventEmitter(this);
    }
}
