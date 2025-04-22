// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
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
    public static void calculateDeGen(@NonNull final EventImpl event) {
        final int maxParentDeGen = Math.max(parentDeGen(event.getSelfParent()), parentDeGen(event.getOtherParent()));
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
