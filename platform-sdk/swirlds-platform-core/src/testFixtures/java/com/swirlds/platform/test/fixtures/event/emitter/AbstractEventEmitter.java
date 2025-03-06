// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.emitter;

import com.swirlds.platform.test.fixtures.event.generator.GraphGenerator;

/**
 * Base class for all event emitters. Contains a {@link GraphGenerator} from which events are emitted according to
 * subclass implementations.
 */
public abstract class AbstractEventEmitter implements EventEmitter {

    private GraphGenerator graphGenerator;

    /**
     * The next event count checkpoint.
     */
    private long checkpoint = Long.MAX_VALUE;

    /**
     * The total number of events that have been emitted by this generator.
     */
    protected long numEventsEmitted;

    protected AbstractEventEmitter(final GraphGenerator graphGenerator) {
        this.graphGenerator = graphGenerator;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GraphGenerator getGraphGenerator() {
        return graphGenerator;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCheckpoint(final long checkpoint) {
        this.checkpoint = checkpoint;
    }

    protected long getCheckpoint() {
        return checkpoint;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final long getNumEventsEmitted() {
        return numEventsEmitted;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        graphGenerator.reset();
        numEventsEmitted = 0;
    }
}
