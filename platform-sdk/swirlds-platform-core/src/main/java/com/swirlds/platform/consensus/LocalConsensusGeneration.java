// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.EventDescriptorWrapper;

/**
 * Local consensus generation (cGen) is computed by the consensus algorithm and used for ordering of events that reach
 * consensus during a round. If two consensus events have the same cGen, the event hash will be used to break the tie.
 * This value needs to be deterministic across all nodes in the network. The value of cGen is temporary, it is only used
 * while ordering consensus events within a single round. Once the round is complete, the cGen value is no longer needed
 * and can be cleared.
 */
public class LocalConsensusGeneration {
    /** The generation value that indicates that the event has not been assigned a cGen value. */
    public static final int GENERATION_UNDEFINED = 0;
    /** The generation value that indicates that the event is the first generation of events in the round. */
    public static final int FIRST_GENERATION = 1;
    /** Clears the cGen for an event */
    private static final Consumer<EventImpl> CLEAR_CGEN = e -> e.setCGen(GENERATION_UNDEFINED);

    /**
     * Assigns the cGen value to the events in the list. The cGen value will be relative to the parents of the event if
     * the parent is in the list.
     *
     * @param events the list of events to assign cGen values to
     */
    public static void assignCGen(@NonNull final List<EventImpl> events) {
        Objects.requireNonNull(events);
        events.sort(Comparator.comparingLong(e -> e.getBaseEvent().getNGen()));

        final Map<EventDescriptorWrapper, EventImpl> parentMap = new HashMap<>();
        for (final EventImpl event : events) {
            final int maxParentGen = event.getBaseEvent().getAllParents().stream()
                    .map(parentMap::get)
                    .mapToInt(LocalConsensusGeneration::getGeneration)
                    .max()
                    .orElse(GENERATION_UNDEFINED);
            event.setCGen(maxParentGen == GENERATION_UNDEFINED ? FIRST_GENERATION : maxParentGen + 1);
            parentMap.put(event.getBaseEvent().getDescriptor(), event);
        }
    }

    /**
     * Clears the cGen value for the events in the list. The cGen value will be set to {@link #GENERATION_UNDEFINED}.
     *
     * @param events the list of events to clear cGen values from
     */
    public static void clearCGen(@NonNull final List<EventImpl> events) {
        Objects.requireNonNull(events);
        events.forEach(CLEAR_CGEN);
    }

    /**
     * Gets the generation of the event. If the event is null, returns {@link #GENERATION_UNDEFINED}.
     *
     * @param event the event to get the generation from
     * @return the generation of the event
     */
    private static int getGeneration(@Nullable final EventImpl event) {
        return event == null ? GENERATION_UNDEFINED : event.getCGen();
    }
}
