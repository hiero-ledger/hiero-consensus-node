// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.emitter;

import com.swirlds.platform.test.fixtures.event.generator.GraphGenerator;

/**
 * Generates an {@link EventEmitter} that emits events using the {@link GraphGenerator} instance provided.
 */
@FunctionalInterface
public interface EventEmitterGenerator {

    /**
     * Creates an {@link EventEmitter} that emits events using the {@link GraphGenerator} instance provided.
     *
     * @param graphGenerator
     * 		the graph generator used to create the graph of events to emit
     * @param seed
     * 		a seed for randomness, if necessary
     * @return the {@link EventEmitter}
     */
    EventEmitter getEventEmitter(GraphGenerator graphGenerator, long seed);
}
