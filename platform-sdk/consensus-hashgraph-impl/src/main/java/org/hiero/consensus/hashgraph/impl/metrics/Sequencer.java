package org.hiero.consensus.hashgraph.impl.metrics;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.hashgraph.impl.EventImpl;

/**
 * Assigns an auto-incrementing sequence number to events as they are added to the system. This is used for metrics purposes.
 */
public class Sequencer {
    /** The first sequence number assigned to an event. */
    public static final long FIRST_SEQUENCE = 1L;
    /** A sequence number that indicates that the event has not been assigned a sequence number. */
    public static final long NO_SEQUENCE = -1L;
    /** The next sequence number to assign to an event. */
    private long nextIndex = FIRST_SEQUENCE;
    /** The last event that was added to the system. */
    private EventImpl lastEventAdded = null;

    /**
     * Assigns a sequence number to the given event and updates the last event added. The sequence number is assigned
     * in the order that events are added to the system.
     *
     * @param event the event to assign a sequence number to
     */
    public void assignSequenceNumber(@NonNull final EventImpl event){
        event.setSequence(nextIndex);
        nextIndex++;
        lastEventAdded = event;
    }

    /**
     * Gets the last event that was added to the system
     *
     * @return the last event that was added to the system, or null if no events have been added
     */
    public @Nullable EventImpl getLastEventAdded() {
        return lastEventAdded;
    }
}
