/*
 * Copyright (C) 2018-2025 Hedera Hashgraph, LLC
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
