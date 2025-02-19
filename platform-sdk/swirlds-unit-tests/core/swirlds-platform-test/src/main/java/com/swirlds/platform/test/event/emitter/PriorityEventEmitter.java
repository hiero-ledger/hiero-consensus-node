/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
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

import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.event.generator.GraphGenerator;
import java.util.List;

/**
 * Emits events based on node priority while maintaining a topologically correct order.
 */
public class PriorityEventEmitter extends BufferingEventEmitter {

    /**
     * A list of node priorities. Lower index nodes have higher priority, so the highest priority node id appears first
     * in this list.
     */
    private final List<Integer> nodePriorities;

    /**
     * Creates a new instance using the provided {@link GraphGenerator} as the source of events and a list of node
     * priorities that determines the order in which events should be emitted. Each call to {@link #emitEvent()} will
     * attempt to emit an event from the highest priority node (index zero in the list). If the highest priority node's
     * next event is not able to be emitted because either of its parents have not yet been emitted, then attempt to
     * emit the next event from the next highest priority node (index 1 in the list). Continue in priority order
     * until an event can be emitted.
     *
     * @param graphGenerator
     * 		the source of events
     * @param nodePriorities
     * 		node is in prior order with the highest priority at index 0 and lowest priority node at the last index
     */
    public PriorityEventEmitter(final GraphGenerator graphGenerator, final List<Integer> nodePriorities) {
        super(graphGenerator);
        this.nodePriorities = nodePriorities;
    }

    public PriorityEventEmitter(final PriorityEventEmitter that) {
        this(that.getGraphGenerator().cleanCopy(), that.nodePriorities);
        this.setCheckpoint(that.getCheckpoint());
    }

    @Override
    public EventImpl emitEvent() {
        // Emit the next event from the highest priority node, if possible. If not possible, try the next priority node.
        // Repeat in priority order until an event can be emitted.
        for (final int nodeIndex : nodePriorities) {
            final AddressBook addressBook = getGraphGenerator().getAddressBook();
            final NodeId nodeId = addressBook.getNodeId(nodeIndex);
            attemptToGenerateEventFromNode(nodeId);
            if (isReadyToEmitEvent(nodeId)) {
                eventEmittedFromBuffer();
                return events.get(nodeId).remove();
            }
        }
        // Should not be possible
        return null;
    }

    /**
     * Returns a copy of this object as it was first created.
     */
    @Override
    public PriorityEventEmitter cleanCopy() {
        return new PriorityEventEmitter(this);
    }

    /**
     * {@inheritDoc}
     * <p>
     * This class does not use randomness to determine the next emitted event, so this method is equivalent to calling
     * {@link #cleanCopy()}.
     * </p>
     */
    @Override
    public PriorityEventEmitter cleanCopy(final long seed) {
        return new PriorityEventEmitter(this);
    }
}
