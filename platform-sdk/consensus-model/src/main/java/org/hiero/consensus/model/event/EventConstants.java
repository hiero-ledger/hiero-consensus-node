// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

import org.hiero.consensus.model.node.NodeId;

public final class EventConstants {
    /**
     * Private constructor so that this class is never instantiated
     */
    private EventConstants() {}

    /**
     * the generation number used to represent that the generation is not defined.
     * an event's computed generation number is always non-negative.
     * in case it is used as a parent generation, it means there is no parent event
     */
    public static final long GENERATION_UNDEFINED = -1;
    /** the ID number used to represent that the ID is undefined */
    public static final NodeId CREATOR_ID_UNDEFINED = NodeId.UNDEFINED_NODE_ID;
    /** the smallest round an event can belong to */
    public static final long MINIMUM_ROUND_CREATED = 1;
    /** the round number to represent that the birth round is undefined */
    public static final long BIRTH_ROUND_UNDEFINED = -1;
    /** represent either a birth round or a generation which is undefined */
    public static final long ANCIENT_THRESHOLD_UNDEFINED = -1;
    /** the minimum generation value an event can have. */
    public static final long FIRST_GENERATION = 0;
}
