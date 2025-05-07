// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.sequence.map.SequenceMap;

/**
 * The non-deterministic generation calculated locally by each node. NGen is calculated for every event that is not an
 * orphan. The value can differ between nodes for the same event and must only ever be used for determining one of the
 * several valid topological orderings, or determining which event is higher in the hashgraph than another (a higher
 * number indicates the event is higher in the hashgraph). NGen will be {@link #GENERATION_UNDEFINED} until set at the
 * appropriate point in the pipeline.
 */
public class NonDeterministicGeneration {

    /** The generation value that indicates that the event has not been assigned an nGen value. */
    public static final long GENERATION_UNDEFINED = 0;
    /** The generation value that indicates that the event is the first generation of events. */
    public static final long FIRST_GENERATION = 1;

    private NonDeterministicGeneration() {}

    /**
     * Assigns the non-deterministic generation to an event. The nGen value is relative to the parents of the event if
     * the parent is in the list.
     *
     * @param event             the event to set the nGen of
     * @param eventsWithParents all non-ancient, non-orphaned events
     */
    public static void assignNGen(
            @NonNull final PlatformEvent event,
            @NonNull final SequenceMap<EventDescriptorWrapper, PlatformEvent> eventsWithParents) {
        long maxParentNGen = GENERATION_UNDEFINED;
        for (final EventDescriptorWrapper parentDesc : event.getAllParents()) {
            final PlatformEvent parent = eventsWithParents.get(parentDesc);
            if (parent != null) {
                maxParentNGen = Math.max(maxParentNGen, parent.getNGen());
            }
        }
        final long nGen = maxParentNGen == GENERATION_UNDEFINED ? FIRST_GENERATION : maxParentNGen + 1;
        event.setNGen(nGen);
    }
}
