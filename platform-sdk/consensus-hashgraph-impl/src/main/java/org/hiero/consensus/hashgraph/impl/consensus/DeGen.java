// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;

/**
 * Local Deterministic Generation (DeGen) is assigned to events deterministically across all nodes prior to events
 * reaching consensus. It is only calculated for the descendants of the last round's judges and used to calculate the
 * last seen event (i.e. lastSee()) and is calculated every time metadata is recalculated.
 */
public class DeGen {
    /**
     * The generation value that indicates that the event has not been assigned a DeGen value. (this event is not a
     * degen)
     */
    public static final int GENERATION_UNDEFINED = 0;
    /** The first DeGen value */
    public static final int FIRST_GENERATION = 1;

    /**
     * Calculates and sets the DeGen value for the event.
     * @param event the event to set the DeGen value for
     */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    public static void calculateDeGen(@NonNull final EventImpl event) {
        int maxParentDeGen = GENERATION_UNDEFINED;
        final List<EventImpl> allParents = event.getAllParents();
        // an index loop, so that no iterator is allocated on this hot path
        for (int i = 0, allParentsSize = allParents.size(); i < allParentsSize; i++) {
            final EventImpl parent = allParents.get(i);
            maxParentDeGen = Math.max(maxParentDeGen, parentDeGen(parent));
        }
        if (maxParentDeGen == GENERATION_UNDEFINED) {
            event.setDeGen(FIRST_GENERATION);
        } else {
            event.setDeGen(maxParentDeGen + 1);
        }
    }

    /**
     * Gets the DeGen value for the parent event.
     * @param event the event to get the DeGen value for
     * @return the DeGen value
     */
    public static int parentDeGen(@Nullable final EventImpl event) {
        return event == null || event.getRoundCreated() == ConsensusConstants.ROUND_NEGATIVE_INFINITY
                ? GENERATION_UNDEFINED
                : event.getDeGen();
    }

    /**
     * Clears the DeGen value for the event.
     * @param event the event to clear the DeGen value for
     */
    public static void clearDeGen(@NonNull final EventImpl event) {
        event.setDeGen(GENERATION_UNDEFINED);
    }
}
