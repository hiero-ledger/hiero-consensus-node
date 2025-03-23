// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.emitter;

import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.event.generator.GraphGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import org.hiero.consensus.model.node.NodeId;

/**
 * A base event emitter class that buffers events created by the {@link GraphGenerator}. Buffering events allows
 * subclasses to emit events in an order different from the order they are generated.
 */
public abstract class BufferingEventEmitter extends AbstractEventEmitter {

    /**
     * The maximum number of events that this generator is permitted to buffer.
     */
    private static final int MAX_BUFFERED_EVENTS = 1_000;

    /**
     * A list of queues, each containing buffered events from an event source. There is one queue per source.
     * The queue at index 0 corresponds to the source with node ID 0, and so on. Events are strongly ordered within
     * an individual queue.
     */
    protected Map<NodeId, Queue<EventImpl>> events;

    /**
     * The number of events that are currently buffered by this generator.
     */
    protected int bufferedEvents;

    protected long numEventsGenerated;

    protected BufferingEventEmitter(final GraphGenerator graphGenerator) {
        super(graphGenerator);
        clearEvents();
    }

    /**
     * Generates 0 or more events that are internally buffered. Events will be generated until there is at least one
     * buffered event from the given node ID or until the buffer fills up.
     */
    protected void attemptToGenerateEventFromNode(@NonNull final NodeId nodeID) {
        Objects.requireNonNull(nodeID, "nodeID");
        while (events.get(nodeID).isEmpty()
                && bufferedEvents < MAX_BUFFERED_EVENTS
                && getCheckpoint() > numEventsGenerated) {
            final EventImpl nextEvent = getGraphGenerator().generateEvent();
            numEventsGenerated++;
            events.get(nextEvent.getCreatorId()).add(nextEvent);
            bufferedEvents++;
        }
    }

    protected void eventEmittedFromBuffer() {
        bufferedEvents--;
        numEventsEmitted++;
    }

    protected void clearEvents() {
        final AddressBook addressBook = getGraphGenerator().getAddressBook();
        events = new HashMap<>(getGraphGenerator().getNumberOfSources());
        for (int index = 0; index < getGraphGenerator().getNumberOfSources(); index++) {
            events.put(addressBook.getNodeId(index), new LinkedList<>());
        }
        bufferedEvents = 0;
    }

    /**
     * Checks to see if a given node is ready to emit an event:
     * <ul>
     *     <li>Events can not be emitted if all of their ancestors have not yet been emitted.</li>
     *     <li>Events can not be emitted if their generator index is not less than the current active checkpoint.</li>
     * </ul>
     */
    protected boolean isReadyToEmitEvent(@NonNull final NodeId nodeID) {
        Objects.requireNonNull(nodeID, "nodeID");
        final EventImpl potentialEvent = events.get(nodeID).peek();
        if (potentialEvent == null) {
            return false;
        }

        final EventImpl otherParent = potentialEvent.getOtherParent();

        if (otherParent == null) {
            // There is no other parent, so no need to wait for it to be emitted
            return true;
        }

        final NodeId otherNodeID = otherParent.getCreatorId();

        for (final EventImpl event : events.get(otherNodeID)) {
            if (event == otherParent) {
                // Our other parent has not yet been emitted
                return false;
            }
        }

        return true;
    }

    @Override
    public void reset() {
        super.reset();
        clearEvents();
        numEventsGenerated = 0;
    }
}
