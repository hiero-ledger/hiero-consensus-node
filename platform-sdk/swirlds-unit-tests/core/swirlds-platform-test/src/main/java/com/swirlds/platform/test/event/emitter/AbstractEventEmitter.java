/*
 * Copyright (C) 2016-2025 Hedera Hashgraph, LLC
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void setGraphGeneratorSeed(final long seed) {
        graphGenerator = graphGenerator.cleanCopy(seed);
    }
}
