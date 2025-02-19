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
